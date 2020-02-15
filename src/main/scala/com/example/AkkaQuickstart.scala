package com.example


import akka.actor
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import com.example.Cache.CacheRequests

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Random, Success}

case class RequestId(s: String)

case class DeviceId(s: String)

object Cache {

  sealed trait CacheRequests
  final case class Get(requestId: RequestId, replyTo: ActorRef[CacheResponses]) extends CacheRequests
  final case class CachedDevices(devices: List[DeviceId]) extends CacheResponses
  final private case class Devices(devices: List[DeviceId]) extends CacheRequests

  sealed trait CacheResponses
  final case object EmptyCache extends CacheResponses
  final private case object Tick extends CacheRequests
  final private case object CacheRefreshFailed extends CacheRequests

  private case object TimerKey

  val empty: Behavior[CacheRequests] =
    Behaviors.withTimers { timer =>
      timer.startPeriodicTimer(TimerKey, Tick, 5.second)

      Behaviors.setup { context =>
        refreshCache(context.self, context.system)

        Behaviors.receiveMessage[CacheRequests] {
          case Get(requestId, replyTo) =>
            context.log.info("Empty cache request for requestId {}.", requestId)
            replyTo ! EmptyCache
            Behaviors.same
          case Devices(devices) =>
            context.log.info("Initialized cache.")
            cached(devices)
          case CacheRefreshFailed =>
            context.log.error("Failed to initialize cache. Will retry.")
            Behaviors.same
          case Tick =>
            context.log.info("Refreshing cache.")
            refreshCache(context.self, context.system)
            Behaviors.same
        }
      }
    }

  private def cached(devices: List[DeviceId]): Behavior[CacheRequests] =
    Behaviors.receive { (context, message) =>
      message match {
        case Get(requestId, replyTo) =>
          context.log.info("Cache request for requestId {}.", requestId)
          replyTo ! CachedDevices(devices)
          Behaviors.same
        case Devices(devices) =>
          context.log.info("Updated cache.")
          cached(devices)
        case CacheRefreshFailed =>
          context.log.error("Failed to refresh cache.")
          Behaviors.same
        case Tick =>
          context.log.info("Refreshing cache.")
          refreshCache(context.self, context.system)
          Behaviors.same
      }
    }

  private def refreshCache(self: ActorRef[CacheRequests], system: ActorSystem[Nothing]): Unit = {
    import akka.actor.typed.scaladsl.adapter._
    implicit val untypedSystem: actor.ActorSystem = system.toClassic
    implicit val ec: ExecutionContextExecutor = untypedSystem.dispatcher

    val random = new Random()

    // From a device source
    Future.successful(List.fill(100)(random.nextInt(100).toString).map(DeviceId)).onComplete {
      case Success(devices) => self ! Devices(devices)
      case Failure(_) => self ! CacheRefreshFailed
    }
  }
}

object SimpleCache extends App {
  val cache = ActorSystem[CacheRequests](Cache.empty, "cache")
}
