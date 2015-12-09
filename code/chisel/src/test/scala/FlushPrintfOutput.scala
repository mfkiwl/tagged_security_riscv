/*
 Copyright (c) 2011, 2012, 2013, 2014, 2015 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

import scala.util.matching.Regex
import org.junit.Assert._
import org.junit.Test
import org.junit.Ignore

import Chisel._
import FlushPrintfOutput._

object FlushPrintfOutput {
    val whiteSpaceRE = """\s""".r
    def eliminateWhiteSpace(s: String): String = whiteSpaceRE.replaceAllIn(s, "")
}

class FlushPrintfOutput extends TestSuite {
  @Test def testFlushPrintfOutput() {
    println("\ntestFlushPrintfOutput ...")

    class PrintfModule extends Module {
      val io = new DecoupledUIntIO
      val counter = Reg(UInt(width = 8), init = UInt(0))
      val counterString = "counter = %d\n"
      counter := counter + UInt(1)
      printf(counterString, counter);
    }

    // TODO: better way to check logs? logging is lower than tests, so it sometimes fails...
    trait FlushPrintfOutputTests extends Tests {
      val expectedOutputs = collection.mutable.ArrayBuffer[String]()
      def tests(m: PrintfModule) {
        for (i <- 0 until 4) {
          step(1)
          expectedOutputs += m.counterString.format(i)
        }
        (printfs zip expectedOutputs) foreach {case (printf, expected) =>
          assertTrue("incorrect output - %s".format(printf), 
            eliminateWhiteSpace(printf) == eliminateWhiteSpace(expected))
        }
      }
    }

    class FlushPrintfOutputTester(m: PrintfModule) extends Tester(m) with FlushPrintfOutputTests {
      tests(m)
    }
    launchCppTester((m: PrintfModule) => new FlushPrintfOutputTester(m))
  }
}
