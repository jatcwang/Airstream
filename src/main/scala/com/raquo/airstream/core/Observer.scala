package com.raquo.airstream.core

import com.raquo.airstream.core.AirstreamError.{ObserverError, ObserverErrorHandlingError}
import com.raquo.airstream.debug.DebuggableObserver

import scala.scalajs.js
import scala.util.{Failure, Success, Try}

trait Observer[-A] extends Sink[A] with Named {

  lazy val toJsFn1: js.Function1[A, Unit] = onNext

  /** Note: must not throw! */
  def onNext(nextValue: A): Unit

  /** Note: must not throw! */
  def onError(err: Throwable): Unit

  /** Note: must not throw! */
  def onTry(nextValue: Try[A]): Unit

  /** Creates another Observer such that calling its onNext will call this observer's onNext
    * with the value processed by the `project` function.
    *
    * This is useful when you need to pass down an Observer[A] to a child component
    * which should not know anything about the type A, but both child and parent know
    * about type `B`, and the parent knows how to translate B into A.
    *
    * @param project Note: guarded against exceptions
    */
  def contramap[B](project: B => A): Observer[B] = {
    Observer.withRecover(
      nextValue => onNext(project(nextValue)),
      { case nextError => onError(nextError) }
    )
  }

  /**
   * Similar to contramap, but does not emit event if the transformation function yields a None
   *
   * Example use case:
   * Parsing numbers from a controlled text input and you want to prevent a value
   * that is not a valid numeric string.
   * @param project Note: guarded against exceptions
   */
  def contramapOpt[B](project: B => Option[A]): Observer[B] = {
    Observer.withRecover(
      nextValue => project(nextValue) match {
        case Some(v) => onNext(v)
        case None => // do nothing
      },
      { case nextError => onError(nextError) }
    )
  }

  /** @param project must not throw! */
  def contramapTry[B](project: Try[B] => Try[A]): Observer[B] = {
    Observer.fromTry { case nextValue => onTry(project(nextValue)) }
  }

  /** Available only on Observers of Option, this is a shortcut for contramap[B](Some(_)) */
  def contramapSome[V](implicit evidence: Option[V] <:< A): Observer[V] = {
    contramap[V](value => evidence(Some(value)))
  }

  /** Like `contramap` but with `collect` semantics: not calling the original observer when `pf` is not defined */
  def contracollect[B](pf: PartialFunction[B, A]): Observer[B] = {
    Observer.withRecover(
      nextValue => pf.runWith(onNext)(nextValue),
      { case nextError => onError(nextError) }
    )
  }

  /** Creates another Observer such that calling its onNext will call this observer's onNext
    * with the same value, but only if it passes the test.
    *
    * @param passes Note: guarded against exceptions
    */
  def filter[B <: A](passes: B => Boolean): Observer[B] = {
    Observer.withRecover(nextValue => if (passes(nextValue)) onNext(nextValue), {
      case nextError => onError(nextError)
    })
  }

  /** Creates another Observer such that calling it calls the original observer after the specified delay. */
  def delay(ms: Int): Observer[A] = {
    Observer.fromTry { case nextValue =>
      js.timers.setTimeout(ms.toDouble) {
        onTry(nextValue)
      }
    }
  }

  override def toObserver: Observer[A] = this
}

object Observer {

  private val _empty = Observer[Any](_ => ())

  /** An observer that does nothing. Use it to ensure that an Observable is started
    *
    * Used by SignalView and EventStreamView
    */
  def empty[A]: Observer[A] = _empty

  /** Provides debug* methods for observers */
  implicit def toDebuggableObserver[A](observer: Observer[A]): DebuggableObserver[A] = new DebuggableObserver(observer)

  /** @param onNext Note: guarded against exceptions */
  def apply[A](onNext: A => Unit): Observer[A] = {
    withRecover(onNext, onError = PartialFunction.empty)
  }

  /** @param onNext Note: guarded against exceptions */
  def ignoreErrors[A](onNext: A => Unit): Observer[A] = {
    withRecover(onNext, onError = { case _ => () })
  }

  /**
    * @param onNext               Note: guarded against exceptions. See docs for details.
    * @param onError              Note: guarded against exceptions. See docs for details.
    * @param handleObserverErrors If true, we will call this observer's onError(ObserverError(err))
    *                             if this observer throws while processing an incoming event,
    *                             giving this observer one last chance to process its own error.
    */
  def withRecover[A](
    onNext: A => Unit,
    onError: PartialFunction[Throwable, Unit],
    handleObserverErrors: Boolean = true
  ): Observer[A] = {
    val onNextParam = onNext // It's beautiful on the outside
    val onErrorParam = onError
    new Observer[A] {

      override def onNext(nextValue: A): Unit = {
        // dom.console.log(s"===== Observer(${hashCode()}).onNext", nextValue.asInstanceOf[js.Any])
        try {
          onNextParam(nextValue)
        } catch {
          case err: Throwable =>
            if (handleObserverErrors) {
              this.onError(ObserverError(err)) // this doesn't throw, see below
            } else {
              AirstreamError.sendUnhandledError(ObserverError(err))
            }
        }
      }

      override def onError(error: Throwable): Unit = {
        try {
          if (onErrorParam.isDefinedAt(error)) {
            onErrorParam(error)
          } else {
            AirstreamError.sendUnhandledError(error)
          }
        } catch {
          case err: Throwable =>
            AirstreamError.sendUnhandledError(ObserverErrorHandlingError(error = err, cause = error))
        }
      }

      override def onTry(nextValue: Try[A]): Unit = {
        nextValue.fold(onError, onNext)
      }
    }
  }

  /** @param onTry                Note: guarded against exceptions. See docs for details.
    * @param handleObserverErrors If true, we will call this observer's onError(ObserverError(err))
    *                             if this observer throws while processing an incoming event,
    *                             giving this observer one last chance to process its own error.
    */
  def fromTry[A](
    onTry: PartialFunction[Try[A], Unit],
    handleObserverErrors: Boolean = true
  ): Observer[A] = {
    val onTryParam = onTry

    new Observer[A] {

      override def onNext(nextValue: A): Unit = {
        // dom.console.log(s"===== Observer(${hashCode()}).onNext", nextValue.asInstanceOf[js.Any])
        onTry(Success(nextValue))
      }

      override def onError(error: Throwable): Unit = {
        onTry(Failure(error))
      }

      override def onTry(nextValue: Try[A]): Unit = {
        try {
          if (onTryParam.isDefinedAt(nextValue)) {
            onTryParam(nextValue)
          } else {
            nextValue.fold(err => AirstreamError.sendUnhandledError(err), _ => ())
          }
        } catch {
          case err: Throwable =>
            if (handleObserverErrors && nextValue.isSuccess) {
              this.onError(ObserverError(err)) // this calls onTry so it doesn't throw
            } else {
              nextValue.fold(
                originalError => AirstreamError.sendUnhandledError(ObserverErrorHandlingError(error = err, cause = originalError)),
                _ => AirstreamError.sendUnhandledError(ObserverError(err))
              )
            }
        }
      }
    }
  }

  /** Combine several observers into one. */
  def combine[A](observers: Observer[A]*): Observer[A] = {
    new Observer[A] {

      override def onNext(nextValue: A): Unit = {
        observers.foreach(_.onNext(nextValue))
      }

      override def onError(err: Throwable): Unit = {
        observers.foreach(_.onError(err))
      }

      override def onTry(nextValue: Try[A]): Unit = {
        observers.foreach(_.onTry(nextValue))
      }
    }
  }
}
