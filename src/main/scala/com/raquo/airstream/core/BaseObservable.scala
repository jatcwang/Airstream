package com.raquo.airstream.core

import com.raquo.airstream.debug.Debugger
import com.raquo.airstream.flatten.FlattenStrategy
import com.raquo.airstream.ownership.{Owner, Subscription}

import scala.annotation.unused
import scala.scalajs.js
import scala.util.Try

/** This trait represents a reactive value that can be subscribed to.
  *
  * It has only one direct subtype, [[Observable]], which in turn has two direct subtypes, [[EventStream]] and [[Signal]].
  *
  * [[BaseObservable]] is the same as [[Observable]], it just lives in a separate trait for technical reasons (the Self type param).
  *
  * All Observables are lazy. An Observable starts when it gets its first observer (internal or external),
  * and stops when it loses its last observer (again, internal or external).
  *
  * Basic idea: Lazy Observable only holds references to those children that have any observers
  * (either directly on themselves, or on any of their descendants). What this achieves:
  * - Stream only propagates its value to children that (directly or not) have observers
  * - Stream calculates its value only once regardless of how many observers / children it has)
  *   (so, all streams are "hot" observables)
  * - Stream doesn't hold references to Streams that no one observes, allowing those Streams
  *   to be garbage collected if they are otherwise unreachable (which they should become
  *   when their subscriptions are killed by their owners)
  */
trait BaseObservable[+Self[+_] <: Observable[_], +A] extends Source[A] with Named {

  /** When subclassing Observable **outside of com.raquo.airstream package**, just make this field public:
    *
    *     override val topoRank: Int = ???
    *
    * "protected[airstream]" will allow this. See https://github.com/raquo/Airstream/issues/37
    */
  protected[airstream] val topoRank: Int

  /** Note: Observer can be added more than once to an Observable.
    * If so, it will observe each event as many times as it was added.
    */
  protected[this] val externalObservers: ObserverList[Observer[A]] = new ObserverList(js.Array())

  /** Note: This is enforced to be a Set outside of the type system #performance */
  protected[this] val internalObservers: ObserverList[InternalObserver[A]] = new ObserverList(js.Array())

  /** @param project Note: guarded against exceptions */
  def map[B](project: A => B): Self[B]

  /** `value` is passed by name, so it will be evaluated whenever the Observable fires.
    * Use it to sample mutable values (e.g. myInput.ref.value in Laminar).
    *
    * See also: [[mapToStrict]]
    *
    * @param value Note: guarded against exceptions
    */
  def mapTo[B](value: => B): Self[B] = map(_ => value)

  /** `value` is evaluated strictly, only once, when this method is called.
    *
    * See also: [[mapTo]]
    */
  def mapToStrict[B](value: B): Self[B] = map(_ => value)

  // @TODO[API] Not sure if `distinct` `should accept A => Key or (A, A) => Boolean. We'll start with a more constrained version for now.
  // @TODO[API] Implement this. We should consider making a slide() operator to support this

  /** Emit a value unless its key matches the key of the last emitted value */
  // def distinct[Key](key: A => Key): Self[A]

  /** @param compose Note: guarded against exceptions */
  @inline def flatMap[B, Inner[_], Output[+_] <: Observable[_]](compose: A => Inner[B])(
    implicit strategy: FlattenStrategy[Self, Inner, Output]
  ): Output[B] = {
    strategy.flatten(map(compose))
  }

  def toStreamIfSignal[B >: A](ifSignal: Signal[A] => EventStream[B]): EventStream[B] = {
    this match {
      case s: Signal[A @unchecked] => ifSignal(s)
      case s: EventStream[A @unchecked] => s
    }
  }

  def toSignalIfStream[B >: A](ifStream: EventStream[A] => Signal[B]): Signal[B] = {
    this match {
      case s: EventStream[A @unchecked] => ifStream(s)
      case s: Signal[A @unchecked] => s
    }
  }

  /** Convert this observable to a signal of Option[A]. If it is a stream, set initial value to None. */
  def toWeakSignal: Signal[Option[A]] = {
    map(Some(_)) match {
      case s: EventStream[Option[A @unchecked] @unchecked] => s.toSignal(initial = None)
      case s: Signal[Option[A @unchecked] @unchecked] => s
    }
  }

  // @TODO[API] I don't like the Option[O] output type here very much. We should consider a sentinel error object instead (need to check performance). Or maybe add a recoverOrSkip method or something?
  /** @param pf Note: guarded against exceptions */
  def recover[B >: A](pf: PartialFunction[Throwable, Option[B]]): Self[B]

  def recoverIgnoreErrors: Self[A] = recover[A]{ case _ => None }

  /** Convert this to an observable that emits Failure(err) instead of erroring */
  def recoverToTry: Self[Try[A]]

  /** Create a new observable that listens to this one and has a debugger attached.
    *
    * Use the resulting observable in place of the original observable in your code.
    * See docs for details.
    *
    * There are more convenient methods available implicitly from [[DebuggableObservable]] and [[DebuggableSignal]],
    * such as debugLog(), debugSpyEvents(), etc.
    */
  def debugWith(debugger: Debugger[A]): Self[A]

  /** Create an external observer from a function and subscribe it to this observable.
    *
    * Note: since you won't have a reference to the observer, you will need to call Subscription.kill() to unsubscribe
    * */
  def foreach(onNext: A => Unit)(implicit owner: Owner): Subscription = {
    val observer = Observer(onNext)
    addObserver(observer)(owner)
  }

  /** Subscribe an external observer to this observable */
  def addObserver(observer: Observer[A])(implicit owner: Owner): Subscription = {
    val subscription = new Subscription(owner, () => Transaction.removeExternalObserver(this, observer))
    externalObservers.push(observer)
    onAddedExternalObserver(observer)
    maybeStart()
    //dom.console.log(s"Adding subscription: $subscription")
    subscription
  }

  @inline protected def onAddedExternalObserver(@unused observer: Observer[A]): Unit = ()

  /** Child observable should call this method on its parents when it is started.
    * This observable calls [[onStart]] if this action has given it its first observer (internal or external).
    */
  protected[airstream] def addInternalObserver(observer: InternalObserver[A]): Unit = {
    // @TODO Why does simple "protected" not work? Specialization?
    internalObservers.push(observer)
    maybeStart()
  }

  /** Child observable should call Transaction.removeInternalObserver(parent, childInternalObserver) when it is stopped.
    * This observable calls [[onStop]] if this action has removed its last observer (internal or external).
    */
  protected[airstream] def removeInternalObserverNow(observer: InternalObserver[A]): Unit = {
    val removed = internalObservers.removeObserverNow(observer)
    if (removed) {
      maybeStop()
    }
  }


  protected[airstream] def removeExternalObserverNow(observer: Observer[A]): Unit = {
    val removed = externalObservers.removeObserverNow(observer)
    if (removed) {
      maybeStop()
    }
  }

  private[this] def numAllObservers: Int = externalObservers.length + internalObservers.length

  protected[this] def isStarted: Boolean = numAllObservers > 0

  /** This method is fired when this observable starts working (listening for parent events and/or firing its own events),
    * that is, when it gets its first Observer (internal or external).
    *
    * [[onStart]] can potentially be called multiple times, the second time being after it has stopped (see [[onStop]]).
    */
  @inline protected[this] def onStart(): Unit = ()

  /** This method is fired when this observable stops working (listening for parent events and/or firing its own events),
    * that is, when it loses its last Observer (internal or external).
    *
    * [[onStop]] can potentially be called multiple times, the second time being after it has started again (see [[onStart]]).
    */
  @inline protected[this] def onStop(): Unit = ()

  private[this] def maybeStart(): Unit = {
    val isStarting = numAllObservers == 1
    if (isStarting) {
      // We've just added first observer
      onStart()
    }
  }

  private[this] def maybeStop(): Unit = {
    if (!isStarted) {
      // We've just removed last observer
      onStop()
    }
  }

  // === A note on performance with error handling ===
  //
  // Signals remember their current value as Try[A], whereas
  // EventStream-s normally fire plain A values and do not need them
  // wrapped in Try. To make things more complicated, user-provided
  // callbacks like `project` in `.map(project)` need to be wrapped in
  // Try() for safety.
  //
  // A worst case performance scenario would see Airstream constantly
  // wrapping and unwrapping the values being propagated, initializing
  // many Success() objects as we walk along the observables dependency
  // graph.
  //
  // We avoid this by keeping the values unwrapped as much as possible
  // in event streams, but wrapping them in signals and state. When
  // switching between streams and memory observables and vice versa
  // we have to pay a small price to wrap or unwrap the value. It's a
  // miniscule penalty that doesn't matter, but if you're wondering
  // how we decide whether to implement onTry or onNext+onError in a
  // particular InternalObserver, this is one of the main factors.
  //
  // With this in mind, you can see fireValue / fireError / fireTry
  // implementations in EventStream and Signal are somewhat
  // redundant (non-DRY), but performance friendly.
  //
  // You must be careful when overriding these methods however, as you
  // don't know which one of them will be called, but they need to be
  // implemented to produce similar results

  protected[this] def fireValue(nextValue: A, transaction: Transaction): Unit

  protected[this] def fireError(nextError: Throwable, transaction: Transaction): Unit

  protected[this] def fireTry(nextValue: Try[A], transaction: Transaction): Unit
}
