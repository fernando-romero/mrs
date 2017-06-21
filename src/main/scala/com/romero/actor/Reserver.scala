package com.romero.actor

import akka.actor.{Actor, Props, Stash}
import akka.pattern.pipe
import com.romero.http.{RegisterScreening, ReserveSeat, Screening}
import com.romero.service.{ImdbService, PersistenceService}

import scala.concurrent.ExecutionContext.Implicits.global

object Reserver {
  def props(persistenceService: PersistenceService, imdbService: ImdbService): Props = Props(new Reserver(persistenceService, imdbService))
}

class Reserver(persistenceService: PersistenceService, imdbService: ImdbService) extends Actor with Stash {

  def receive: Receive = {
    case rs: RegisterScreening =>
      pipe(persistenceService.getScreening(rs.imdbId, rs.screenId)).to(self, sender)
      context.become(registering(rs))

    case rs: ReserveSeat =>
      pipe(persistenceService.getScreening(rs.imdbId, rs.screenId)).to(self, sender)
      context.become(reserving(rs))
  }

  def registering(rs: RegisterScreening): Receive = {
    case Some(_) =>
      sender ! ScreeningAlreadyRegistered
      context.unbecome()
      unstashAll()

    case None =>
      val title = imdbService.getTitle(rs.imdbId).getOrElse("NA")
      val screening = Screening(rs.imdbId, rs.screenId, title, rs.availableSeats, 0)
      pipe(persistenceService.registerScreening(screening).map(_ => screening)).to(self, sender)

    case s: Screening =>
      sender ! s
      context.unbecome()
      unstashAll()

    case f: akka.actor.Status.Failure =>
      sender ! f
      context.unbecome()
      unstashAll()

    case _ => stash()
  }

  def reserving(rs: ReserveSeat): Receive = {
    case Some(screening: Screening) =>
      if (screening.availableSeats > 0) {
        val updated = screening.copy(
          availableSeats = screening.availableSeats - 1,
          reservedSeats = screening.reservedSeats + 1
        )
        pipe(persistenceService.updateScreening(updated).map(_ => updated)).to(self, sender)
      } else {
        sender ! NoSeatsAvailable
        context.unbecome()
        unstashAll()
      }

    case None =>
      sender ! ScreeningNotFound
      context.unbecome()
      unstashAll()

    case s: Screening =>
      sender ! s
      context.unbecome()
      unstashAll()

    case f: akka.actor.Status.Failure =>
      sender ! f
      context.unbecome()
      unstashAll()

    case _ => stash()
  }
}
