package com.romero.service

import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.scraper.ContentExtractors.element

import scala.util.Try

trait ImdbService {
  def getTitle(imdbId: String): Option[String]
}

class ImdbServiceImpl extends ImdbService {
  private val browser = JsoupBrowser()

  override def getTitle(imdbId: String): Option[String] = {
    for {
      doc <- Try(browser.get(s"http://www.imdb.com/title/$imdbId/")).toOption
      element <- doc >?> element("title")
    } yield {
      element.innerHtml
    }
  }
}
