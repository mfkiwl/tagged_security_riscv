// See LICENSE for license details.

package uncore
package constants

import Chisel._

object MemoryOpConstants extends MemoryOpConstants
trait MemoryOpConstants {
  val MT_SZ = 4
  val MT_X  = BitPat("b????")
  val MT_B  = UInt("b0000")
  val MT_H  = UInt("b0001")
  val MT_W  = UInt("b0010")
  val MT_D  = UInt("b0011")
  val MT_BU = UInt("b0100")
  val MT_HU = UInt("b0101")
  val MT_WU = UInt("b0110")
  val MT_Q  = UInt("b0111")
  val MT_T  = UInt("b1111") // tag

  val NUM_XA_OPS = 9 // ?? not used
  val M_SZ      = 5
  val M_X       = BitPat("b?????");
  val M_XRD     = UInt("b00000"); // int load
  val M_XWR     = UInt("b00001"); // int store
  val M_PFR     = UInt("b00010"); // prefetch with intent to read
  val M_PFW     = UInt("b00011"); // prefetch with intent to write
  val M_XA_SWAP = UInt("b00100");
  val M_NOP     = UInt("b00101");
  val M_XLR     = UInt("b00110");
  val M_XSC     = UInt("b00111");
  val M_XA_ADD  = UInt("b01000");
  val M_XA_XOR  = UInt("b01001");
  val M_XA_OR   = UInt("b01010");
  val M_XA_AND  = UInt("b01011");
  val M_XA_MIN  = UInt("b01100");
  val M_XA_MAX  = UInt("b01101");
  val M_XA_MINU = UInt("b01110");
  val M_XA_MAXU = UInt("b01111");
  val M_FLUSH   = UInt("b10000") // write back dirty data and cede R/W permissions
  val M_PRODUCE = UInt("b10001") // write back dirty data and cede W permissions
  val M_CLEAN   = UInt("b10011") // write back dirty data and retain R/W permissions

  def isAMO(cmd: UInt) = cmd(3) || cmd === M_XA_SWAP
  def isPrefetch(cmd: UInt) = cmd === M_PFR || cmd === M_PFW
  def isRead(cmd: UInt) = cmd === M_XRD || cmd === M_XLR || cmd === M_XSC || isAMO(cmd)
  def isWrite(cmd: UInt) = cmd === M_XWR || cmd === M_XSC || isAMO(cmd)
  def isWriteIntent(cmd: UInt) = isWrite(cmd) || cmd === M_PFW || cmd === M_XLR
}

object CSR
{
  // commands
  val SZ = 3
  val X = BitPat.DC(SZ)
  val N = UInt(0,SZ)
  val W = UInt(1,SZ)
  val S = UInt(2,SZ)
  val C = UInt(3,SZ)
  val I = UInt(4,SZ)
  val R = UInt(5,SZ)
}
