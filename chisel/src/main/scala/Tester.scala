/*
 Copyright (c) 2011, 2012, 2013, 2014 The Regents of the University of
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

package Chisel
import scala.collection.mutable.{ArrayBuffer, HashMap, Queue => ScalaQueue}
import scala.collection.immutable.ListSet
import scala.util.Random
import java.nio.channels.FileChannel
import java.lang.Double.{longBitsToDouble, doubleToLongBits}
import java.lang.Float.{intBitsToFloat, floatToIntBits}
import scala.sys.process.{Process, ProcessLogger}
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

// Provides a template to define tester transactions
trait Tests {
  def t: Int 
  def delta: Int 
  def rnd: Random
  def setClocks(clocks: Iterable[(Clock, Int)]): Unit
  def peek(data: Bits): BigInt
  def peek(data: Aggregate): Array[BigInt]
  def peek(data: Flo): Float
  def peek(data: Dbl): Double
  def peekAt[T <: Bits](data: Mem[T], off: Int): BigInt
  def poke(data: Bits, x: Boolean): Unit
  def poke(data: Bits, x: Int): Unit
  def poke(data: Bits, x: Long): Unit
  def poke(data: Bits, x: BigInt): Unit
  def poke(data: Aggregate, x: Array[BigInt]): Unit
  def poke(data: Flo, x: Float): Unit 
  def poke(data: Dbl, x: Double): Unit
  def pokeAt[T <: Bits](data: Mem[T], value: BigInt, off: Int): Unit
  def reset(n: Int = 1): Unit
  def step(n: Int): Unit
  def int(x: Boolean): BigInt 
  def int(x: Int):     BigInt 
  def int(x: Long):    BigInt 
  def int(x: Bits):    BigInt 
  def expect (good: Boolean, msg: => String): Boolean
  def expect (data: Bits, expected: BigInt): Boolean
  def expect (data: Aggregate, expected: Array[BigInt]): Boolean
  def expect (data: Bits, expected: Int): Boolean
  def expect (data: Bits, expected: Long): Boolean
  def expect (data: Flo, expected: Float): Boolean
  def expect (data: Dbl, expected: Double): Boolean
  def printfs: Vector[String]
  def run(s: String): Boolean
}

case class TestApplicationException(exitVal: Int, lastMessage: String) extends RuntimeException(lastMessage)

/** This class is the super class for test cases
  * @param c The module under test
  * @param isTrace print the all I/O operations and tests to stdout, default true
  * @example
  * {{{ class myTest(c : TestModule) extends Tester(c) { ... } }}}
  */
class Tester[+T <: Module](c: T, isTrace: Boolean = true) extends FileSystemUtilities {
  var t = 0 // simulation time
  var delta = 0
  private val _pokeMap = HashMap[Bits, BigInt]()
  private val _peekMap = HashMap[Bits, BigInt]()
  private val _signalMap = HashMap[String, Int]()
  private val _chunks = HashMap[String, Int]()
  private val _clocks = Driver.clocks map (clk => clk -> clk.period.round.toInt)
  private val _clockLens = HashMap(_clocks:_*)
  private val _clockCnts = HashMap(_clocks:_*)
  val (_inputs: ListSet[Bits], _outputs: ListSet[Bits]) = ListSet(c.wires.unzip._2: _*) partition (_.dir == INPUT)
  private var isStale = false
  // Return any accumulated module printf output since the last call.
  private var _lastLogIndex = 0
  private def newTestOutputString: String = {
    val result = _logs.slice(_lastLogIndex, _logs.length) mkString("\n")
    _lastLogIndex = _logs.length
    result
  }
  private val _logs = new ArrayBuffer[String]()
  def printfs = _logs.toVector

  // A busy-wait loop that monitors exitValue so we don't loop forever if the test application exits for some reason.
  private def mwhile(block: => Boolean)(loop: => Unit) {
    while (!exitValue.isCompleted && block) {
      loop
    }
    // If the test application died, throw a run-time error.
    if (exitValue.isCompleted) {
      // We assume the error string is the last log entry.
      val errorString = _logs.last
      println(newTestOutputString)
      throw new TestApplicationException(Await.result(exitValue, Duration(-1, SECONDS)), errorString)
    }
  }
  private object SIM_CMD extends Enumeration { 
    val RESET, STEP, UPDATE, POKE, PEEK, FORCE, GETID, GETCHK, SETCLK, FIN = Value }
  implicit def cmdToId(cmd: SIM_CMD.Value) = cmd.id

  private class Channel(name: String) {
    private lazy val file = new java.io.RandomAccessFile(name, "rw")
    private lazy val channel = file.getChannel
    @volatile private lazy val buffer = channel map (FileChannel.MapMode.READ_WRITE, 0, channel.size)
    implicit def intToByte(i: Int) = i.toByte
    def aquire {
      buffer put (0, 1)
      buffer put (2, 0)
      while((buffer get 1) == 1 && (buffer get 2) == 0) {}
    }
    def release { buffer put (0, 0) }
    def ready = (buffer get 3) == 0
    def valid = (buffer get 3) == 1
    def produce { buffer put (3, 1) }
    def consume { buffer put (3, 0) }
    def update(idx: Int, data: Long) { buffer putLong (8*idx+4, data) }
    def update(base: Int, data: String) { 
      data.zipWithIndex foreach {case (c, i) => buffer put (base+i+4, c) }
      buffer put (base+data.size+4, 0)
    }
    def apply(idx: Int): Long = buffer getLong (8*idx+4)
    def close { file.close }
    buffer order java.nio.ByteOrder.nativeOrder
    new java.io.File(name).delete
  }

  private lazy val inChannel = new Channel("channel.in")
  private lazy val outChannel = new Channel("channel.out")
  private lazy val cmdChannel = new Channel("channel.cmd")

  def dumpName(data: Node): String = Driver.backend match {
    case _: FloBackend => data.getNode.name
    case _ => data.getNode.chiselName
  }

  def setClock(clk: Clock, len: Int) {
    _clockLens(clk) = len
    _clockCnts(clk) = len
    mwhile(!sendCmd(SIM_CMD.SETCLK)) { }
    mwhile(!sendCmd(clk.name)) { }
    mwhile(!sendValue(len, 1)) { }
  }

  def setClocks(clocks: Iterable[(Clock, Int)]) {
    clocks foreach { case (clk, len) => setClock(clk, len) }
  }

  def signed_fix(dtype: Bits, rv: BigInt): BigInt = {
    val w = dtype.needWidth()
    dtype match {
      /* Any "signed" node */
      case _: SInt | _ : Flo | _: Dbl => (if(rv >= (BigInt(1) << w - 1)) (rv - (BigInt(1) << w)) else rv)
      /* anything else (i.e., UInt) */
      case _ => (rv)
    }
  }

  private def peek(id: Int, chunk: Int) = {
    mwhile(!sendCmd(SIM_CMD.PEEK)) { }
    mwhile(!sendCmd(id)) { }
    if (exitValue.isCompleted) {
      BigInt(0)
    } else {
      (for {
        _ <- Stream.from(1)
        data = recvValue(chunk)
        if data != None
      } yield data.get).head
    }
  }
  /** Peek at the value of a node based on the path
    */
  def peekPath(path: String): BigInt = {
    val id = _signalMap getOrElseUpdate (path, getId(path))
    if (id == -1) {
      println("Can't find id for '%s'".format(path))
      id
    } else {
      peek(id, _chunks getOrElseUpdate (path, getChunk(id)))
    }
  }
  /** Peek at the value of a node
    * @param node Node to peek at
    * @param off The index or offset to inspect */
  def peekNode(node: Node, off: Option[Int] = None) = {
    val i = off match { case Some(p) => s"[${p}]" case None => "" }
    peekPath(s"${dumpName(node)}${i}")
  }
  /** Peek at the value of some memory at an index
    * @param data Memory to inspect
    * @param off Offset in memory to look at */
  def peekAt[T <: Bits](data: Mem[T], off: Int): BigInt = {
    val value = peekNode(data, Some(off))
    if (isTrace) println("  PEEK %s[%d] -> %x".format(dumpName(data), off, value))
    value
  }
  /** Peek at the value of some bits
    * @return a BigInt representation of the bits */
  def peek(data: Bits): BigInt = {
    if (isStale) update
    val value = 
      if (data.isLit) data.litValue()
      else if (data.isTopLevelIO && data.dir == INPUT) _pokeMap(data)
      else signed_fix(data, _peekMap getOrElse (data, peekNode(data.getNode)))
    if (isTrace) println("  PEEK %s -> %x".format(dumpName(data), value))
    value
  }
  /** Peek at Aggregate data
    * @return an Array of BigInts representing the data */
  def peek(data: Aggregate): Array[BigInt] = {
    data.flatten.map(x => x._2) map (peek(_))
  }
  /** Interpret data as a single precision float */
  def peek(data: Flo): Float = {
    intBitsToFloat(peek(data.asInstanceOf[Bits]).toInt)
  }
  /** Interpret the data as a double precision float */
  def peek(data: Dbl): Double = {
    longBitsToDouble(peek(data.asInstanceOf[Bits]).toLong)
  }

  private def poke(id: Int, chunk: Int, v: BigInt, force: Boolean = false) { 
    val cmd = if (!force) SIM_CMD.POKE else SIM_CMD.FORCE
    mwhile(!sendCmd(cmd)) { }
    mwhile(!sendCmd(id)) { }
    mwhile(!sendValue(v, chunk)) { }
  }
  /** set the value of a node with its path
    * @param path The unique path of the node to set
    * @param v The BigInt representing the bits to set
    * @example {{{ poke(path, BigInt(63) << 60, 2) }}}
    */
  def pokePath(path: String, v: BigInt, force: Boolean = false) {
    val id = _signalMap getOrElseUpdate (path, getId(path)) 
    if (id == -1) {
      println("Can't find id for '%s'".format(path))
    } else {
      poke(id, _chunks getOrElseUpdate (path, getChunk(id)), v, force)
    }
  }
  /** set the value of a node
    * @param node The node to set
    * @param v The BigInt representing the bits to set
    * @param off The offset or index
    */
  def pokeNode(node: Node, v: BigInt, off: Option[Int] = None) {
    val i = off match { case Some(p) => s"[${p}]" case None => "" }
    pokePath(s"${dumpName(node)}${i}", v)
  }
  /** set the value of some memory
    * @param data The memory to write to
    * @param value The BigInt representing the bits to set
    * @param off The offset representing the index to write to memory
    */
  def pokeAt[T <: Bits](data: Mem[T], value: BigInt, off: Int): Unit = {
    if (isTrace) println("  POKE %s[%d] <- %x".format(dumpName(data), off, value))
    pokeNode(data, value, Some(off))
  }
  /** Set the value of some 'data' Node */
  def poke(data: Bits, x: Boolean) { this.poke(data, int(x)) }
  /** Set the value of some 'data' Node */
  def poke(data: Bits, x: Int)     { this.poke(data, int(x)) }
  /** Set the value of some 'data' Node */
  def poke(data: Bits, x: Long)    { this.poke(data, int(x)) }
  /** Set the value of some 'data' Node */
  def poke(data: Bits, x: BigInt)  {
    val value = if (x >= 0) x else {
      val cnt = (data.needWidth() - 1) >> 6
      ((0 to cnt) foldLeft BigInt(0))((res, i) => res | (int((x >> (64 * i)).toLong) << (64 * i)))
    }
    data.getNode match {
      case _: Delay =>
        if (isTrace) println("  POKE %s <- %x".format(dumpName(data), value))
        pokeNode(data.getNode, value)
        isStale = true
      case _ if data.isTopLevelIO && data.dir == INPUT =>
        if (isTrace) println("  POKE %s <- %x".format(dumpName(data), value))
        _pokeMap(data) = value
        isStale = true
      case _ =>
        if (isTrace) println(s"  NOT ALLOWED POKE ${dumpName(data)}")
    }
  }
  /** Set the value of Aggregate data */
  def poke(data: Aggregate, x: Array[BigInt]): Unit = {
    val kv = (data.flatten.map(x => x._2), x.reverse).zipped
    for ((x, y) <- kv) poke(x, y)
  }
  /** Set the value of a hardware single precision floating point representation */
  def poke(data: Flo, x: Float): Unit = {
    poke(data.asInstanceOf[Bits], BigInt(floatToIntBits(x)))
  }
  /** Set the value of a hardware double precision floating point representation */
  def poke(data: Dbl, x: Double): Unit = {
    poke(data.asInstanceOf[Bits], BigInt(doubleToLongBits(x)))
  }

  private def sendCmd(data: Int) = {
    cmdChannel.aquire
    val ready = cmdChannel.ready
    if (ready) {
      cmdChannel(0) = data
      cmdChannel.produce
    }
    cmdChannel.release
    ready
  }

  private def sendCmd(data: String) = {
    cmdChannel.aquire
    val ready = cmdChannel.ready
    if (ready) {
      cmdChannel(0) = data
      cmdChannel.produce
    }
    cmdChannel.release
    ready
  }

  private def recvResp = {
    outChannel.aquire
    val valid = outChannel.valid
    val resp = if (!valid) None else {
      outChannel.consume
      Some(outChannel(0).toInt)
    }
    outChannel.release
    resp
  }

  private def sendValue(value: BigInt, chunk: Int) = {
    inChannel.aquire
    val ready = inChannel.ready
    if (ready) {
      (0 until chunk) foreach (i => inChannel(i) = (value >> (64*i)).toLong)
      inChannel.produce
    }
    inChannel.release
    ready
  }

  private def recvValue(chunk: Int) = {
    outChannel.aquire
    val valid = outChannel.valid
    val value = if (!valid) None else {
      outChannel.consume
      Some(((0 until chunk) foldLeft BigInt(0))(
        (res, i) => res | (BigInt(outChannel(i)) << (64*i))))
    }
    outChannel.release
    value
  }

  private def sendInputs = {
    inChannel.aquire
    val ready = inChannel.ready
    if (ready) {
      (_inputs.toList foldLeft 0){case (off, in) =>
        val chunk = _chunks(dumpName(in))
        val value = _pokeMap getOrElse (in, BigInt(0))
        (0 until chunk) foreach (i => inChannel(off+i) = (value >> (64*i)).toLong)
        off + chunk
      }
      inChannel.produce
    }
    inChannel.release
    ready
  }

  private def recvOutputs = {
    _peekMap.clear
    outChannel.aquire
    val valid = outChannel.valid
    if (valid) {
      (_outputs.toList foldLeft 0){case (off, out) =>
        val chunk = _chunks(dumpName(out))
        _peekMap(out) = ((0 until chunk) foldLeft BigInt(0))(
          (res, i) => res | (int(outChannel(off+i)) << (64*i)))
        off + chunk
      }        
      outChannel.consume
    }
    outChannel.release
    valid
  }

  /** Send reset to the hardware
    * @param n number of cycles to hold reset for, default 1 */
  def reset(n: Int = 1) {
    if (isTrace) println(s"RESET ${n}")
    for (i <- 0 until n) {
      mwhile(!sendCmd(SIM_CMD.RESET)) { }
      mwhile(!recvOutputs) { }
    }
  }

  protected def update {
    mwhile(!sendCmd(SIM_CMD.UPDATE)) { }
    mwhile(!sendInputs) { }
    mwhile(!recvOutputs) { }
    isStale = false
  }

  private def calcDelta = {
    val min = (_clockCnts.values foldLeft Int.MaxValue)(math.min(_, _))
    _clockCnts.keys foreach (_clockCnts(_) -= min)
    (_clockCnts filter (_._2 == 0)).keys foreach (k => _clockCnts(k) = _clockLens(k)) 
    min
  }

  protected def takeStep {
    mwhile(!sendCmd(SIM_CMD.STEP)) { }
    mwhile(!sendInputs) { }
    delta += calcDelta
    mwhile(!recvOutputs) { }
    // dumpLogs
    if (isTrace) println(newTestOutputString)
    isStale = false
  }

  protected def getId(path: String) = {
    mwhile(!sendCmd(SIM_CMD.GETID)) { }
    mwhile(!sendCmd(path)) { }
    if (exitValue.isCompleted) {
      0
    } else {
      (for {
        _ <- Stream.from(1)
        data = recvResp
        if data != None
      } yield data.get).head
    }
  }

  protected def getChunk(id: Int) = {
    mwhile(!sendCmd(SIM_CMD.GETCHK)) { }
    mwhile(!sendCmd(id)) { }
    if (exitValue.isCompleted){
      0
    } else {
      (for {
        _ <- Stream.from(1)
        data = recvResp
        if data != None
      } yield data.get).head
    }
  }

  /** Step time by the smallest amount to the next rising clock edge
    * @note this is defined based on the period of the clock
    * See [[Chisel.Clock$ Clock]]
    */
  def step(n: Int) {
    if (isTrace) println(s"STEP ${n} -> ${t+n}")
    (0 until n) foreach (_ => takeStep)
    t += n
  }

  /** Convert a Boolean to BigInt */
  def int(x: Boolean): BigInt = if (x) 1 else 0
  /** Convert an Int to BigInt */
  def int(x: Int):     BigInt = (BigInt(x >>> 1) << 1) | x & 1
  /** Convert a Long to BigInt */
  def int(x: Long):    BigInt = (BigInt(x >>> 1) << 1) | x & 1
  /** Convert Bits to BigInt */
  def int(x: Bits):    BigInt = x.litValue()

  var ok = true
  var failureTime = -1

  /** Indicate a failure has occurred.  */
  def fail() {
    ok = false
    if (failureTime == -1) {
      failureTime = t
    }
  }

  /** Expect a value to be true printing a message if it passes or fails
    * @param good If the test passed or not
    * @param msg The message to print out
    */
  def expect (good: Boolean, msg: => String): Boolean = {
    if (isTrace) println(s"""${msg} ${if (good) "PASS" else "FAIL"}""")
    if (!good) { fail() }
    good
  }

  /** Expect the value of data to have the same bits as a BigInt */
  def expect (data: Bits, expected: BigInt): Boolean = {
    val mask = (BigInt(1) << data.needWidth) - 1
    val got = peek(data) & mask
    val exp = expected & mask
    expect(got == exp, "EXPECT %s <- %x == %x".format(dumpName(data), got, exp))
  }

  /** Expect the value of Aggregate data to be have the values as passed in with the array */
  def expect (data: Aggregate, expected: Array[BigInt]): Boolean = {
    val kv = (data.flatten.map(x => x._2), expected.reverse).zipped
    var allGood = true
    for ((d, e) <- kv)
      allGood = expect(d, e) && allGood
    allGood
  }

  /** Expect the value of 'data' to be 'expected'
    * @return the test passed */
  def expect (data: Bits, expected: Int): Boolean = {
    expect(data, int(expected))
  }
  /** Expect the value of 'data' to be 'expected'
    * @return the test passed */
  def expect (data: Bits, expected: Long): Boolean = {
    expect(data, int(expected))
  }
  /* We need the following so scala doesn't use our "tolerant" Float version of expect.
   */
  /** Expect the value of 'data' to be 'expected'
    * @return the test passed */
  def expect (data: Flo, expected: Float): Boolean = {
    val got = peek(data)
    expect(got == expected, "EXPECT %s <- %s == %s".format(dumpName(data), got, expected))
  }
  /** Expect the value of 'data' to be 'expected'
    * @return the test passed */
  def expect (data: Dbl, expected: Double): Boolean = {
    val got = peek(data)
    expect(got == expected, "EXPECT %s <- %s == %s".format(dumpName(data), got, expected))
  }

  /* Compare the floating point value of a node with an expected floating point value.
   * We will tolerate differences in the bottom bit.
   */
  /** A tolerant expect for Float
    * Allows for a single least significant bit error in the floating point representation */
  def expect (data: Bits, expected: Float): Boolean = {
    val gotBits = peek(data).toInt
    val expectedBits = java.lang.Float.floatToIntBits(expected)
    var gotFLoat = java.lang.Float.intBitsToFloat(gotBits)
    var expectedFloat = expected
    if (gotFLoat != expectedFloat) {
      val gotDiff = gotBits - expectedBits
      // Do we have a single bit difference?
      if (scala.math.abs(gotDiff) <= 1) {
        expectedFloat = gotFLoat
      }
    }
    expect(gotFLoat == expectedFloat, "EXPECT %s <- %s == %s".format(dumpName(data), gotFLoat, expectedFloat))
  }

  _signalMap ++= Driver.signalMap flatMap {
    case (m: Mem[_], id) => 
      (0 until m.n) map (idx => "%s[%d]".format(dumpName(m), idx) -> (id + idx))
    case (node, id) => Seq(dumpName(node) -> id)
  }

  Driver.dfs { 
    case m: Mem[_] => (0 until m.n) foreach {idx => 
      val name = s"${dumpName(m)}[${idx}]"
      _chunks(name) = (m.needWidth-1)/64 + 1
    }
    case node if node.isInObject => 
      _chunks(dumpName(node)) = (node.needWidth-1)/64 + 1
    case _ =>
  }

  // Always use a specific seed so results (whenever) are reproducible.
  val rnd = new Random(Driver.testerSeed)
  val process: Process = {
    val n = Driver.appendString(Some(c.name),Driver.chiselConfigClassName)
    val target = s"${Driver.targetDir}/${n}"
    // If the caller has provided a specific command to execute, use it.
    val cmd = Driver.testCommand match {
      case Some(cmd) => cmd
      case None => Driver.backend match {
        case b: FloBackend =>
          val command = ArrayBuffer(b.floDir + "fix-console", ":is-debug", "true", ":filename", target + ".hex", ":flo-filename", target + ".mwe.flo")
          if (Driver.isVCD) { command ++= ArrayBuffer(":is-vcd-dump", "true") }
          if (Driver.emitTempNodes) { command ++= ArrayBuffer(":emit-temp-nodes", "true") }
          command ++= ArrayBuffer(":target-dir", Driver.targetDir)
          command.mkString(" ")
        case b: VerilogBackend => List(target, "-q", "+vcs+initreg+0", 
          if (Driver.isVCD) "+vpdfile=%s.vpd".format(Driver.targetDir + c.name)  else "",
          if (Driver.isVCDMem) "+vpdmem" else "") mkString " "
        case _ => target
      }
    }
    val processBuilder = Process(cmd) 
    val processLogger = ProcessLogger(_logs += _)
    val process = processBuilder run processLogger
    println("SEED " + Driver.testerSeed)
    println("STARTING " + cmd)
    while(!new java.io.File("sim.start").exists) Thread.sleep(100)
    new java.io.File("sim.start").delete 
    // Init channels
    inChannel.consume
    cmdChannel.consume
    inChannel.release
    outChannel.release
    cmdChannel.release
    process
  }

  private def start {
    t = 0
    mwhile(!recvOutputs) { }
    // reset(5)
    for (i <- 0 until 5) {
      mwhile(!sendCmd(SIM_CMD.RESET)) { }
      mwhile(!recvOutputs) { }
    }
  }

  /** Complete the simulation and inspect all tests */
  def finish {
    mwhile(!sendCmd(SIM_CMD.FIN)) { }
    if (isTrace) println(newTestOutputString)
    val passMsg = if (ok) "PASSED" else s"FAILED FIRST AT CYCLE ${failureTime}"
    println(s"RAN ${t} CYCLES ${passMsg}")
    process.destroy
    _logs.clear
    inChannel.close
    outChannel.close
    cmdChannel.close
    if(!ok) throwException("Module under test FAILED at least one test vector.")
  }

  // Set up a Future to wait for (and signal) the test process exit.
  private val exitValue: Future[Int] = Future {
    blocking {
      process.exitValue
    }
  }

  // Once everything has been prepared, we can start the communications.
  start
}

/** A tester to check a node graph from INPUTs to OUTPUTs directly */
class MapTester[+T <: Module](c: T, val testNodes: Seq[Node]) extends Tester(c, false) {
  val (ins, outs) = testNodes partition { case b: Bits => b.dir == INPUT case _ => false }
  def step(svars: HashMap[Node, Node],
           ovars: HashMap[Node, Node] = HashMap.empty,
           isTrace: Boolean = true): Boolean = {
    if (isTrace) println("---\nINPUTS")
    ins foreach { in =>
      val value = (svars get in) match { case None => BigInt(0) case Some(v) => v.litValue() }
      in match {
        case io: Bits if io.isTopLevelIO => poke(io, value)
        case _ => pokeNode(in, value)
      }
      if (isTrace) println("  WRITE " + dumpName(in) + " = " + value)
    }
    step(1)
    if (isTrace) println("OUTPUTS")
    outs forall { out =>
      val value = out match { 
        case io: Bits if io.isTopLevelIO => peek(io)
        case _ => peekNode(out)
      }
      (ovars get out) match {
        case None => 
          ovars(out) = Literal(value)
          if (isTrace) println("  READ " + dumpName(out) + " = " + value)
          true
        case Some(e) =>
          val expected = e.litValue()
          val pass = expected == value
          if (isTrace) println("  EXPECTED %s: %x == %x -> %s".format(value, expected, if (pass) "PASS" else "FAIL"))
          pass
      }
    }
  }
  var tests: () => Boolean = () => { println("DEFAULT TESTS"); true }
  def defTests(body: => Boolean) = body
}
