package com.example


import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import com.example.Cache.{CacheRequests, Devices}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Random, Success}

case class RequestId(s: String)

case class DeviceId(s: String)

object Timer {
  private case object TimerKey

  sealed trait TimerMessage
  private case object Tick extends TimerMessage
  private case class FailedRequest(ex: Throwable) extends TimerMessage
  private case class SuccessfulRequest(devices: List[DeviceId]) extends TimerMessage

  def start(cache: ActorRef[CacheRequests], request: => Future[List[DeviceId]]): Behavior[TimerMessage] =
    Behaviors.withTimers { timer =>
      timer.startPeriodicTimer(TimerKey, Tick, 5.seconds)
      Behaviors.receive[TimerMessage] { (context, message) =>
        message match {
          case Tick =>
            context.log.info("Executing request.")
            implicit val ec: ExecutionContextExecutor = context.executionContext
            request.onComplete {
              case Success(devices) => context.self ! SuccessfulRequest(devices)
              case Failure(ex)      => context.self ! FailedRequest(ex)
            }
            Behaviors.same
          case SuccessfulRequest(devices) =>
            context.log.info("Successful response.")
            cache ! Devices(devices)
            Behaviors.same
          case FailedRequest(ex) =>
            context.log.error("Failed response.")
            throw ex
        }
      }
    }
}

object Cache {
  sealed trait CacheRequests
  final case class Get(requestId: RequestId, replyTo: ActorRef[CacheResponses]) extends CacheRequests
  final case class Devices(devices: List[DeviceId]) extends CacheRequests

  sealed trait CacheResponses
  final case object EmptyCache extends CacheResponses
  final case class CachedDevices(devices: List[DeviceId]) extends CacheResponses

  val empty: Behavior[CacheRequests] =
    Behaviors.receive[CacheRequests] { (context, message) =>
      message match {
        case Get(requestId, replyTo) =>
          context.log.info("Empty cache request for requestId {}.", requestId)
          replyTo ! EmptyCache
          Behaviors.same
        case Devices(devices) =>
          context.log.info("Initializing cache.")
          cached(devices)
      }
    }

  private def cached(devices: List[DeviceId]): Behavior[CacheRequests] =
    Behaviors.receive { (context, message) =>
      message match {
        case Get(requestId, replyTo) =>
          context.log.info("Cache request for requestId {}.", requestId)
          replyTo ! CachedDevices(devices)
          Behaviors.same
        case Devices(updatedDevices) =>
          context.log.info("Updating cache.")
          cached(updatedDevices)
      }
    }
}

object Main {
  case class Ping()

  def apply(): Behavior[Ping] = Behaviors.setup {context =>
    val random = new Random()
    def getDevices: Future[List[DeviceId]] =
      Future.successful(List.fill(100)(random.nextInt(100).toString).map(DeviceId))
    val cache = context.spawn(Cache.empty, "cache")
    val timer = Behaviors
    .supervise {
      Timer.start(cache, getDevices)
    }
    .onFailure[Throwable] {
      SupervisorStrategy.restartWithBackoff(
        minBackoff = 0.seconds,
        maxBackoff = 60.seconds,
        randomFactor = 0.1)
    }
    context.spawn(timer, "timer")
    Behaviors.same
  }
}

object SimpleCache extends App {
  val main: ActorSystem[Main.Ping] = ActorSystem(Main(), "main")
}
