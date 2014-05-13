/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package org.kiji.express.flow

import java.io.InputStream

import org.apache.commons.io.IOUtils
import org.apache.hadoop.mapred.JobConf
import org.junit.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.twitter.scalding._
import org.junit.Assert

import org.kiji.schema.Kiji
import org.kiji.schema.KijiURI
import org.kiji.schema.shell.api.Client
import org.kiji.schema.testutil.AbstractKijiIntegrationTest
import org.kiji.schema.util.InstanceBuilder
import cascading.flow.{Flow, FlowListener}
import com.twitter.scalding.Hdfs

class IntegrationTestSimpleFlow extends AbstractKijiIntegrationTest {
  private final val Log: Logger = LoggerFactory.getLogger(classOf[IntegrationTestSimpleFlow])

  private final val TestLayout: String = "layout/org.kiji.express.flow.ITSimpleFlow.ddl"
  private final val TableName: String = "table"

  /**
   * Applies a table's DDL definition on the specified Kiji instance.
   *
   * @param resourcePath Path of the resource containing the DDL to create the table.
   * @param instanceURI URI of the Kiji instance to use.
   * @throws IOException on I/O error.
   */
  def create(resourcePath: String, instanceURI: KijiURI): Unit = {
    val client: Client = Client.newInstance(instanceURI)
    try {
      val ddl: String = readResource(resourcePath)
      Log.info("Executing DDL statement:\n{}", ddl)
      client.executeUpdate(ddl)
    } finally {
      client.close()
    }
  }

  /**
   * Loads a text resource by name.
   *
   * @param resourcePath Path of the resource to load.
   * @return the resource content, as a string.
   * @throws IOException on I/O error.
   */
  def readResource(resourcePath: String): String = {
    Log.info("Reading resource '{}'.", resourcePath)
    val istream: InputStream = getClass.getClassLoader().getResourceAsStream(resourcePath)
    try {
      val content: String = IOUtils.toString(istream)
      Log.info("Resource content is:\n{}", content)
      return content
    } finally {
      istream.close()
    }
  }

  @Test
  def testSimpleFlow(): Unit = {
    val kijiURI = getKijiURI()
    create(TestLayout, kijiURI)

    val kiji = Kiji.Factory.open(kijiURI)
    try {
      val table = kiji.openTable(TableName)
      try {
        new InstanceBuilder()
            .withTable(table)
                .withRow("row1")
                    .withFamily("info")
                        .withQualifier("name").withValue("name1")
                        .withQualifier("email").withValue("email1")
                .withRow("row2")
                    .withFamily("info")
                        .withQualifier("name").withValue("name2")
                        .withQualifier("email").withValue("email2")
            .build().release()
        val args = Mode.putMode(
          Hdfs(false, conf = new JobConf(getConf)),
          Args("--tableURI %s".format(table.getURI().toString)))


//        val c = new JobConf(getConf)
//
//
//        val j = new Job(args)
//        j.uniqueId
//        j.name
//        // Get start and end time manually like in MR
//
//        j.mode match {
//          case Hdfs(_, conf) => conf
//          case _ => println("non-hadoop express job, not logging to job history.")
//        }



        val job: IntegrationTestSimpleFlow.TestJob = new IntegrationTestSimpleFlow.TestJob(args)
        Assert.assertTrue(job.counters.isEmpty)
        Assert.assertTrue(job.run)
        println(job.counters)
      } finally {
        table.release()
      }
    } finally {
      kiji.release()
    }
  }
}

object IntegrationTestSimpleFlow {

  class TestJob(args: Args) extends KijiJob(args) {
    val stat: Stat = Stat("name", "counter")

    KijiInput.builder
      .withTableURI(args("tableURI"))
      .withColumns("info:email" -> 'email)
      .build
      .groupAll { group =>
          group.reduce('email -> 'size) { (acc: Int, next: Int) => stat.inc; acc +1 } }
      .debug
      .write(NullSource)

    override def listeners: List[FlowListener] = {
      val statListener: FlowListener = new FlowListener {
        override def onStopping(flow: Flow[_]): Unit = {}

        override def onStarting(flow: Flow[_]): Unit = {}

        override def onThrowable(flow: Flow[_], throwable: Throwable): Boolean = false

        override def onCompleted(flow: Flow[_]): Unit = {
          val stats = flow.getFlowStats.getCounterValue("counter", "name")
          println("found stats: %s".format(stats))
        }
      }
      statListener :: super.listeners
    }
  }
}
