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

package com.comcast.money.core.async

import com.comcast.money.core.SpecHelpers
import com.comcast.money.core.concurrent.ConcurrentSupport
import org.scalatest.{ Matchers, OneInstancePerTest, WordSpecLike }
import org.scalatest.mock.MockitoSugar

class DirectExecutionContextSpec
    extends WordSpecLike
    with MockitoSugar with Matchers with ConcurrentSupport with OneInstancePerTest with SpecHelpers {

  val underTest = new DirectExecutionContext()

  "DirectExecutionContext" should {
    "execute the Runnable on the current thread" in {
      val currentThreadId = Thread.currentThread().getId
      var callbackThreadId: Long = 0

      underTest.execute(new Runnable {
        override def run(): Unit = {
          callbackThreadId = Thread.currentThread().getId
        }
      })

      callbackThreadId shouldEqual currentThreadId
    }
  }
}
