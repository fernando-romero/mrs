package com.romero.service

import com.romero.http.Screening
import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.Document
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Updates.{combine, set}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

trait PersistenceService {
  def getScreenings: Future[Seq[Screening]]
  def getScreening(imdbId: String, screenId: String): Future[Option[Screening]]
  def registerScreening(screening: Screening): Future[Unit]
  def updateScreening(screening: Screening): Future[Unit]
}

class PersistenceServiceImpl(uri: String) extends PersistenceService {
  private val client = MongoClient(uri)
  private val db = client.getDatabase("mrs")
  private val col = db.getCollection("screenings")

  private def toModel(doc: Document): Option[Screening] = {
    Try {
      Screening(
        imdbId = doc.getString("imdbId"),
        screenId = doc.getString("screenId"),
        movieTitle = doc.getString("movieTitle"),
        availableSeats = doc.getInteger("availableSeats"),
        reservedSeats = doc.getInteger("reservedSeats")
      )
    }.toOption
  }

  private def toDoc(screening: Screening) = {
    Document("imdbId" -> screening.imdbId) +
      ("screenId" -> screening.screenId) +
      ("movieTitle" -> screening.movieTitle) +
      ("availableSeats" -> screening.availableSeats) +
      ("reservedSeats" -> screening.reservedSeats)
  }

  override def getScreenings: Future[Seq[Screening]] = {
    col.find().toFuture().map(_.flatMap(toModel))
  }

  override def getScreening(imdbId: String, screenId: String): Future[Option[Screening]] = {
    val query = and(equal("imdbId", imdbId), equal("screenId", screenId))
    col.find(query).first().head().map(toModel)
  }

  override def registerScreening(screening: Screening): Future[Unit] = {
    col.insertOne(toDoc(screening)).head().map(_ => ())
  }

  override def updateScreening(screening: Screening): Future[Unit] = {
    val query = and(equal("imdbId", screening.imdbId), equal("screenId", screening.screenId))
    val update = combine(
      set("availableSeats", screening.availableSeats),
      set("reservedSeats", screening.reservedSeats))
    col.updateOne(query, update).head().map(_ => ())
  }
}
