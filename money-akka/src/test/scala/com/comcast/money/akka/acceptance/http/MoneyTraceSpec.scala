/*
 * Copyright 2012-2015 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.money.akka.acceptance.http

import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.HttpHeader.ParsingResult.{Error, Ok}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.Source
import com.comcast.money.akka.Blocking.RichFuture
import com.comcast.money.akka.SpanHandlerMatchers.haveSomeSpanName
import com.comcast.money.akka.http._
import com.comcast.money.akka.{AkkaMoneyScope, CollectingSpanHandler, SpanContextWithStack, TestStreams}
import com.comcast.money.api.Span
import com.comcast.money.core.Formatters
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationDouble, FiniteDuration}

class MoneyTraceSpec extends AkkaMoneyScope {

  "A Akka Http route with a MoneyDirective" should {
    "start a span for a request" in {
      Get("/") ~> simpleRoute() ~> check(responseAs[String] shouldBe "response")

      maybeCollectingSpanHandler should haveSomeSpanName(getRoot)
    }

    "continue a span for a request with a span" in {
      import scala.collection.immutable.Seq
      val span: Span = {
        implicit val spanContextWithStack = new SpanContextWithStack
        moneyExtension.tracer.startSpan(tracedHttpRequest)
        spanContextWithStack.current.get
      }

      val header =
        HttpHeader.parse(name = "X-MoneyTrace", value = Formatters.toHttpHeader(span.info.id)) match {
          case Ok(parsedHeader, _) => parsedHeader
          case Error(errorInfo) => throw ParseFailure(errorInfo.summary)
        }

      HttpRequest(headers = Seq(header)) ~> simpleRoute() ~> check(responseAs[String] shouldBe "response")

      maybeCollectingSpanHandler should haveSomeSpanName(getRoot)
    }

    "have the capacity to be named by a user" in {
      implicit val httpSKC: HttpRequestSpanKeyCreator = HttpRequestSpanKeyCreator((_: HttpRequest) => tracedHttpRequest)

      Get("/") ~> simpleRoute() ~> check(responseAs[String] shouldBe "response")

      maybeCollectingSpanHandler should haveSomeSpanName(tracedHttpRequest)
    }

    "trace a chunked response till it completes fully" in {
      Get("/chunked") ~> simpleRoute() ~> check {
        val pattern = "chunk([0-9]+)".r
        val entityString = pattern.findAllIn(Unmarshal(responseEntity).to[String].get()).toSeq

        entityString.take(3) shouldBe Seq("chunk1", "chunk2", "chunk3")
      }

      maybeCollectingSpanHandler should haveARequestDurationLongerThan(120 millis)
    }

    "trace a asynchronous request" in {
      Get("/async") ~> simpleRoute() ~> check(responseAs[String] shouldBe "asyncResponse")

      maybeCollectingSpanHandler should haveSomeSpanName("GET /async")
    }
  }

  def haveARequestDurationLongerThan(expectedTimeTaken: FiniteDuration): Matcher[Option[CollectingSpanHandler]] =
    Matcher {
      maybeCollectingSpanHandler =>
        val requestSpanName = "GET /chunked"
        val maybeSpanInfo =
          maybeCollectingSpanHandler
            .map(_.spanInfoStack)
            .flatMap(_.find(_.name == requestSpanName))

        val maybeMillis = maybeSpanInfo.map(_.durationMicros / 1000)
        MatchResult(
          matches =
            maybeSpanInfo match {
              case Some(spanInfo) => spanInfo.durationMicros >= expectedTimeTaken.toMicros
              case None => false
            },
          rawFailureMessage = s"Duration of Span $requestSpanName was $maybeMillis not Some($expectedTimeTaken)",
          rawNegatedFailureMessage = s"Duration of Span $requestSpanName was $maybeMillis equal to Some($expectedTimeTaken)"
        )
    }

  val testStreams = new TestStreams

  def simpleRoute(source: Source[ChunkStreamPart, _] = testStreams.asyncManyElements)
                 (implicit requestSKC: HttpRequestSpanKeyCreator = DefaultHttpRequestSpanKeyCreator,
                  executionContext: ExecutionContext) =
    get {
      pathSingleSlash {
        MoneyTrace {
          (_: TracedRequest) => TracedResponse(HttpResponse(entity = "response"))
        }
      } ~
        path("chunked") {
          MoneyTrace fromChunkedSource {
            (_: TracedRequest) => source
          }
        } ~
        path("async") {
          MoneyTrace {
            (_: TracedRequest) => Future(TracedResponse(HttpResponse(entity = "asyncResponse")))
          }
      }
    }

  val getRoot = "GET /"
  val tracedHttpRequest = "TracedHttpRequest"

  case class ParseFailure(msg: String) extends Throwable(msg)

}