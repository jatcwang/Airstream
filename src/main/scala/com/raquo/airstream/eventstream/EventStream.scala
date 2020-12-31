package com.raquo.airstream.eventstream

import app.tulz.tuplez.Composition
import com.raquo.airstream.combine.{CombineEventStreamN, CombineObservable}
import com.raquo.airstream.combine.generated.{CombineEventStream2, EventStreamCombineMethods}
import com.raquo.airstream.core.AirstreamError.ObserverError
import com.raquo.airstream.core.{AirstreamError, Observable, Transaction}
import com.raquo.airstream.features.Splittable
import com.raquo.airstream.signal.{FoldLeftSignal, Signal, SignalFromEventStream}

import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

trait EventStream[+A] extends Observable[A] {

  override type Self[+T] = EventStream[T]

  override def map[B](project: A => B): EventStream[B] = {
    new MapEventStream(this, project, recover = None)
  }

  /** @param passes Note: guarded against exceptions */
  def filter(passes: A => Boolean): EventStream[A] = {
    new FilterEventStream(parent = this, passes)
  }

  def filterNot(predicate: A => Boolean): EventStream[A] = filter(!predicate(_))

  /** @param pf Note: guarded against exceptions */
  def collect[B](pf: PartialFunction[A, B]): EventStream[B] = {
    // @TODO[Performance] Use applyOrElse
    filter(pf.isDefinedAt).map(pf)
  }

  /** @param ms milliseconds of delay */
  def delay(ms: Int = 0): EventStream[A] = {
    new DelayEventStream(parent = this, ms)
  }

  /** Make a stream that emits this stream's values but waits for `after` stream to emit first in a given transaction.
    * You can use this for Signals too with `Signal.composeChanges` (see docs for more details)
    */
  def delaySync(after: EventStream[_]): EventStream[A] = {
    new SyncDelayEventStream[A](parent = this, after = after)
  }

  /** See docs for [[ThrottleEventStream]] */
  def throttle(intervalMillis: Int): EventStream[A] = {
    ThrottleEventStream(parent = this, intervalMillis)
  }

  /** See docs for [[DebounceEventStream]] */
  def debounce(delayFromLastEventMillis: Int): EventStream[A] = {
    new DebounceEventStream(parent = this, delayFromLastEventMillis)
  }

  // @TODO[API] Should we introduce some kind of FoldError() wrapper?
  /** @param fn Note: guarded against exceptions */
  def foldLeft[B](initial: B)(fn: (B, A) => B): Signal[B] = {
    foldLeftRecover(
      Success(initial)
    )(
      (currentValue, nextParentValue) => Try(fn(currentValue.get, nextParentValue.get))
    )
  }

  /** @param fn Note: Must not throw! */
  def foldLeftRecover[B](initial: Try[B])(fn: (Try[B], Try[A]) => Try[B]): Signal[B] = {
    new FoldLeftSignal(parent = this, () => initial, fn)
  }

  @inline def startWith[B >: A](initial: => B): Signal[B] = toSignal(initial)

  @inline def startWithTry[B >: A](initial: => Try[B]): Signal[B] = toSignalWithTry(initial)

  @inline def startWithNone: Signal[Option[A]] = toWeakSignal

  override def toSignal[B >: A](initial: => B): Signal[B] = {
    toSignalWithTry(Success(initial))
  }

  def toSignalWithTry[B >: A](initial: => Try[B]): Signal[B] = {
    new SignalFromEventStream(parent = this, initial)
  }

  def toWeakSignal: Signal[Option[A]] = {
    new SignalFromEventStream(parent = this.map(Some(_)), lazyInitialValue = Success(None))
  }

  def compose[B](operator: EventStream[A] => EventStream[B]): EventStream[B] = {
    operator(this)
  }

  def combineWith[B, C](otherEventStream: EventStream[B])(combinator: (A, B) => C): EventStream[C] = {
    new CombineEventStream2[A, B, C](
      parent1 = this,
      parent2 = otherEventStream,
      combinator = combinator
    )
  }

  @inline def combine[B](otherEventStream: EventStream[B])(implicit composition: Composition[A, B]): EventStream[composition.Composed] = {
    combineWith(otherEventStream)(composition.compose)
  }

  /** @param project MUST NOT THROW! */
  def withCurrentValueOfWith[B, C](signal: Signal[B])(project: (A, B) => C): EventStream[C] = {
    new SampleCombineEventStream2[A, B, C](
      samplingStream = this,
      sampledSignal = signal,
      combinator = CombineObservable.tupleCombinator(project)
    )
  }

  @inline def withCurrentValueOf[B](signal: Signal[B])(implicit composition: Composition[A, B]): EventStream[composition.Composed] = {
    withCurrentValueOfWith(signal)(composition.compose)
  }

  def sample[B](signal: Signal[B]): EventStream[B] = {
    new SampleCombineEventStream2[A, B, B](
      samplingStream = this,
      sampledSignal = signal,
      combinator = CombineObservable.tupleCombinator((_, sampledValue) => sampledValue)
    )
  }

  /** See docs for [[MapEventStream]]
    *
    * @param pf Note: guarded against exceptions
    */
  override def recover[B >: A](pf: PartialFunction[Throwable, Option[B]]): Self[B] = {
    new MapEventStream[A, B](
      parent = this,
      project = identity,
      recover = Some(pf)
    )
  }

  override def recoverToTry: EventStream[Try[A]] = map(Try(_)).recover[Try[A]] { case err => Some(Failure(err)) }

  override protected[this] def fireValue(nextValue: A, transaction: Transaction): Unit = {
    // Note: Removal of observers is always done at the end of a transaction, so the iteration here is safe

    // === CAUTION ===
    // The following logic must match Signal's fireTry! It is separated here for performance.

    externalObservers.foreach { observer =>
      try {
        observer.onNext(nextValue)
      } catch {
        case err: Throwable => AirstreamError.sendUnhandledError(ObserverError(err))
      }
    }

    internalObservers.foreach { observer =>
      observer.onNext(nextValue, transaction)
    }
  }

  override protected[this] def fireError(nextError: Throwable, transaction: Transaction): Unit = {
    // Note: Removal of observers is always done at the end of a transaction, so the iteration here is safe

    // === CAUTION ===
    // The following logic must match Signal's fireTry! It is separated here for performance.

    externalObservers.foreach { observer =>
      observer.onError(nextError)
    }

    internalObservers.foreach { observer =>
      observer.onError(nextError, transaction)
    }
  }

  override protected[this] final def fireTry(nextValue: Try[A], transaction: Transaction): Unit = {
    nextValue.fold(
      fireError(_, transaction),
      fireValue(_, transaction)
    )
  }
}

object EventStream extends EventStreamCombineMethods {

  /** Event stream that never emits anything */
  val empty: EventStream[Nothing] = {
    new SeqEventStream[Nothing](events = Nil, emitOnce = true)
  }

  /** @param emitOnce if true, the event will be emitted at most one time.
    *                 If false, the event will be emitted every time the stream is started. */
  @deprecated("Use `fromValue` or `empty` (see docs)", "0.4") // @TODO Are we sure we want to deprecate this?
  def fromSeq[A](events: Seq[A], emitOnce: Boolean): EventStream[A] = {
    new SeqEventStream[A](events.map(Success(_)), emitOnce)
  }

  /** @param emitOnce if true, the event will be emitted at most one time.
    *                 If false, the event will be emitted every time the stream is started. */
  def fromValue[A](event: A, emitOnce: Boolean): EventStream[A] = {
    new SeqEventStream[A](List(Success(event)), emitOnce)
  }

  /** @param emitOnce if true, the event will be emitted at most one time.
    *                 If false, the event will be emitted every time the stream is started. */
  def fromTry[A](value: Try[A], emitOnce: Boolean): EventStream[A] = {
    new SeqEventStream[A](List(value), emitOnce)
  }

  def fromFuture[A](future: Future[A]): EventStream[A] = {
    new FutureEventStream(future, emitIfFutureCompleted = false)
  }

  def fromJsPromise[A](promise: js.Promise[A]): EventStream[A] = {
    fromFuture(promise.toFuture)
  }

  def periodic(
    intervalMs: Int,
    emitInitial: Boolean = true,
    resetOnStop: Boolean = true
  ): PeriodicEventStream[Int] = {
    new PeriodicEventStream[Int](
      initial = 0,
      next = eventNumber => Some((eventNumber + 1, intervalMs)),
      emitInitial = emitInitial,
      resetOnStop = resetOnStop
    )
  }

  def combine[T1, T2](
    stream1: EventStream[T1],
    stream2: EventStream[T2]
  ): EventStream[(T1, T2)] = {
    combineWith(stream1, stream2)(Tuple2.apply[T1, T2])
  }

  def combineWith[T1, T2, Out](
    stream1: EventStream[T1],
    stream2: EventStream[T2]
  )(
    combinator: (T1, T2) => Out
  ): EventStream[Out] = {
    new CombineEventStream2(stream1, stream2, combinator)
  }

  def combineSeq[A](
    streams: Seq[EventStream[A]]
  ): EventStream[Seq[A]] = {
    new CombineEventStreamN[A, Seq[A]](streams, identity)
  }

  def merge[A](streams: EventStream[A]*): EventStream[A] = {
    new MergeEventStream[A](streams)
  }

  def mergeSeq[A](streams: Seq[EventStream[A]]): EventStream[A] = {
    merge(streams: _*) // @TODO[Performance] Does _* introduce any overhead in Scala.js?
  }

  implicit def toTuple2Stream[A, B](stream: EventStream[(A, B)]): Tuple2EventStream[A, B] = {
    new Tuple2EventStream(stream)
  }

  implicit def toSplittableStream[M[_], Input](stream: EventStream[M[Input]]): SplittableEventStream[M, Input] = {
    new SplittableEventStream(stream)
  }

  implicit def toSplittableOneStream[A](stream: EventStream[A]): SplittableOneEventStream[A] = {
    new SplittableOneEventStream(stream)
  }
}
