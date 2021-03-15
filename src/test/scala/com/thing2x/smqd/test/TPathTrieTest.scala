// Copyright 2018 UANGEL
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.thing2x.smqd.test

import com.thing2x.smqd.registry.TPathTrie
import com.thing2x.smqd.{FilterPath, TopicPath}
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.flatspec.AnyFlatSpec

// 2018. 7. 11. - Created by Kwon, Yeong Eon

class TPathTrieTest extends AnyFlatSpec with StrictLogging {
  var trie = TPathTrie[String]()

  "TPathTrie" should "append children" in {
    trie.add(FilterPath(""), context = "empty")
    trie.add(FilterPath("#"), context = "#")
    trie.add(FilterPath("sensor/+/temp"), context = "temp:1")
    trie.add(FilterPath("sensor/+/temp"), context = "temp:2")
    trie.add(FilterPath("sensor/+/temp"), context = "temp:3")
    trie.add(FilterPath("$queue/sensor/+/temp"), context = "temp:q1")
    trie.add(FilterPath("$queue/sensor/+/temp"), context = "temp:q2")
    trie.add(FilterPath("sensor/abc/temp"), context = "abc:temp")
    trie.add(FilterPath("sensor/abc"), context = "abc")
    trie.add(FilterPath("sensor/#"), context = "sensor/#")
    trie.add(FilterPath("houses/mine/bedroom/1/humidity"), "roomhum1")
    trie.add(FilterPath("houses/mine/bedroom/2/humidity"), "roomhum2")
    trie.add(FilterPath("houses/mine/bedroom/3/humidity"), "roomhum3")
    trie.add(FilterPath("houses/mine/bedroom/1/temp"), "roomtemp1")
    trie.add(FilterPath("houses/mine/bedroom/2/temp"), "roomtemp2")
    trie.add(FilterPath("houses/mine/bedroom/3/temp"), "roomtemp3")
    trie.add(FilterPath("/buildings/+/maybe"), context = "building")
    trie.add(FilterPath("/buildings/+/maybe/#"), context = "building-all")
    trie.add(FilterPath("$local/$SYS/protocols/#"), context = "protocol")

    val sb = new StringBuilder()
    trie.dump(sb)
    logger.info(s"\n${sb.toString}")
  }

  it should "matches" in {
    var m1 = trie.matches(TopicPath("sensor/1/temp"))
    assert(m1.toSet == Set("#", "temp:1", "temp:2", "temp:3", "temp:q1", "temp:q2", "sensor/#"))

    m1 = trie.matches(TopicPath("sensor/1/xyz"))
    assert(m1.toSet == Set("#", "sensor/#"))

    m1 = trie.matches(TopicPath("/builds/1"))
    assert(m1.toSet == Set("#"))

    m1 = trie.matches(TopicPath("/buildings/1/maybe"))
    assert(m1.toSet == Set("#", "building", "building-all"))

    m1 = trie.matches(TopicPath("/buildings/1/maybe/abc/xyz"))
    assert(m1.toSet == Set("#", "building-all"))

    m1 = trie.matches(TopicPath("$SYS/protocols"))
    assert(m1.toSet == Set("#", "protocol"))
  }

  "Sub/UnSub" should "selective unsubscription" in {
    trie = TPathTrie[String]()

    trie.add(FilterPath("$SYS/requestors/test-01/#"), context = "r1")
    trie.add(FilterPath("$local/$SYS/faults/#"), context = "f1")
    trie.add(FilterPath("$SYS/protocols"), context = "p1")
    trie.add(FilterPath("$local/$SYS/protocols/#"), context = "p2")

    val sb = new StringBuilder()
    trie.dump(sb)
    logger.info(s"\n${sb.toString}")

    var m = trie.matches(TopicPath("$SYS/requestors/test-01/1"))
    assert(m.length == 1 && m.head == "r1")

    m = trie.matches(TopicPath("$SYS/faults"))
    assert(m.length == 1 && m.head == "f1")

    m = trie.matches(TopicPath("$SYS/protocols"))
    assert(m.length == 2 && m.contains("p1") && m.contains("p2"))

    // remove only one item from trie
    val rs = trie.filter(_ == "p1")
    rs.foreach { r =>
      val noRemains = trie.remove(FilterPath("$SYS/protocols"), r)
      logger.info(s"===> $r   $noRemains")
      assert(noRemains == 0)
    }

    sb.clear()
    trie.dump(sb)
    logger.info(s"\n${sb.toString}")

    m = trie.matches(TopicPath("$SYS/requestors/test-01/1"))
    assert(m.length == 1 && m.head == "r1")

    m = trie.matches(TopicPath("$SYS/faults"))
    assert(m.length == 1 && m.head == "f1")

    m = trie.matches(TopicPath("$SYS/protocols"))
    assert(m.length == 1 && m.head == "p2")
  }
}
