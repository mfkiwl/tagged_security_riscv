// See LICENSE for license details.

package uncore
import Chisel._

//Memory Parameters
case object CacheBlockBytes extends Field[Int]
case object CacheBlockOffsetBits extends Field[Int]

// IO/Mem space
case object NIOSections extends Field[Int]
case object IODataBits extends Field[UInt]
case object NMemSections extends Field[Int]
case object InitIOBase extends Field[String]
case object InitIOMask extends Field[String]
case object InitMemBase extends Field[String]
case object InitMemMask extends Field[String]
case object InitPhyBase extends Field[String]

//Params used by all caches
case object NSets extends Field[Int]
case object NWays extends Field[Int]
case object RowBits extends Field[Int]
case object CacheName extends Field[String]
/** Unique name per TileLink network*/
case object TLId extends Field[String]

// L1 D$
case object StoreDataQueueDepth extends Field[Int]
case object ReplayQueueDepth extends Field[Int]
case object NMSHRs extends Field[Int]
case object LRSCCycles extends Field[Int]
case object ECCCode extends Field[Option[Code]]
case object Replacer extends Field[() => ReplacementPolicy]

// L2 $
case object NAcquireTransactors extends Field[Int]
case object NSecondaryMisses extends Field[Int]
case object L2DirectoryRepresentation extends Field[DirectoryRepresentation]

// Tag $
case object TagBits extends Field[Int]
case object TCBlockBits extends Field[Int]
case object TCTransactors extends Field[Int]
case object TCBlockTags extends Field[Int]
case object TCBaseAddr extends Field[Int]
case object TagTLDataBits extends Field[Int]
case object TagMemSize extends Field[Int]
case object TCTrackers extends Field[Int]
case object TagRowTags extends Field[Int]
case object TagRowBlocks extends Field[Int]
case object TagRowBytes extends Field[Int]
case object TagBlockBlocks extends Field[Int]
case object TagBlockBytes extends Field[Int]
case object TagBlockTagBits extends Field[Int]




// Rocket Core Constants
case object XLen extends Field[Int]

// uncore parameters
case object NBanks extends Field[Int]
case object BankIdLSB extends Field[Int]
case object LNHeaderBits extends Field[Int]
/** Width of cache block addresses */
case object TLBlockAddrBits extends Field[Int]
/** Number of client agents */
case object TLNClients extends Field[Int]
/** Width of data beats */
case object TLDataBits extends Field[Int]
/** Number of bits in write mask (usually one per byte in beat) */
case object TLWriteMaskBits extends Field[Int]
/** Number of data beats per cache block */
case object TLDataBeats extends Field[Int]
/** Whether the underlying physical network preserved point-to-point ordering of messages */
case object TLNetworkIsOrderedP2P extends Field[Boolean]
/** Number of manager agents */
case object TLNManagers extends Field[Int] 
/** Number of client agents that cache data and use custom [[uncore.Acquire]] types */
case object TLNCachingClients extends Field[Int]
/** Number of client agents that do not cache data and use built-in [[uncore.Acquire]] types */
case object TLNCachelessClients extends Field[Int]
/** Coherency policy used to define custom mesage types */
case object TLCoherencePolicy extends Field[CoherencePolicy]
/** Maximum number of unique outstanding transactions per manager */
case object TLMaxManagerXacts extends Field[Int]
/** Maximum number of unique outstanding transactions per client */
case object TLMaxClientXacts extends Field[Int]
/** Maximum number of clients multiplexed onto a single port */
case object TLMaxClientsPerPort extends Field[Int]

//Tile Constants
case object NTiles extends Field[Int]
