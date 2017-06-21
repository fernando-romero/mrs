package com.romero.actor

import akka.actor.{Actor, Props}
import com.romero.http.{RegisterScreening, ReserveSeat}
import com.romero.service.{ImdbService, PersistenceService}

object Manager {
  def props(persistenceService: PersistenceService, imdbService: ImdbService): Props = Props(new Manager(persistenceService, imdbService))
}

class Manager(persistenceService: PersistenceService, imdbService: ImdbService) extends Actor {

  private def getReserver(imdbId: String, screenId: String) = {
    val name = s"$imdbId-$screenId"
    context.child(name).getOrElse {
      context.actorOf(Reserver.props(persistenceService, imdbService), name)
    }
  }

  def receive: Receive = {
    case rs: RegisterScreening =>
      getReserver(rs.imdbId, rs.screenId).forward(rs)
    case rs: ReserveSeat =>
      getReserver(rs.imdbId, rs.screenId).forward(rs)
  }
}
