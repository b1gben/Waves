package com.wavesplatform.state2

import cats.implicits._
import cats.kernel.Monoid
import scorex.account.{Address, Alias, PublicKeyAccount}
import scorex.transaction.Transaction

case class LeaseBalance(in: Long, out: Long)

object LeaseBalance {
  val empty = LeaseBalance(0, 0)

  implicit val m: Monoid[LeaseBalance] = new Monoid[LeaseBalance] {
    override def empty: LeaseBalance = LeaseBalance.empty

    override def combine(x: LeaseBalance, y: LeaseBalance): LeaseBalance =
      LeaseBalance(safeSum(x.in, y.in), safeSum(x.out, y.out))
  }
}

case class VolumeAndFee(volume: Long, fee: Long)

object VolumeAndFee {
  val empty = VolumeAndFee(0, 0)

  implicit val m: Monoid[VolumeAndFee] = new Monoid[VolumeAndFee] {
    override def empty: VolumeAndFee = VolumeAndFee.empty

    override def combine(x: VolumeAndFee, y: VolumeAndFee): VolumeAndFee =
      VolumeAndFee(x.volume + y.volume, x.fee + y.fee)
  }
}

case class AssetDescription(issuer: PublicKeyAccount, name: Array[Byte], decimals: Int, reissuable: Boolean)

case class AssetInfo(isReissuable: Boolean, volume: BigInt)

object AssetInfo {
  implicit val assetInfoMonoid: Monoid[AssetInfo] = new Monoid[AssetInfo] {
    override def empty: AssetInfo = AssetInfo(isReissuable = true, 0)

    override def combine(x: AssetInfo, y: AssetInfo): AssetInfo =
      AssetInfo(x.isReissuable && y.isReissuable, x.volume + y.volume)
  }
}

case class Diff(transactions: Map[ByteStr, (Int, Transaction, Set[Address])],
                portfolios: Map[Address, Portfolio],
                issuedAssets: Map[ByteStr, AssetInfo],
                aliases: Map[Alias, Address],
                paymentTransactionIdsByHashes: Map[ByteStr, ByteStr],
                orderFills: Map[ByteStr, VolumeAndFee],
                leaseState: Map[ByteStr, Boolean]) {

  lazy val accountTransactionIds: Map[Address, List[ByteStr]] = {
    val map: List[(Address, Set[(Int, Long, ByteStr)])] = transactions.toList
      .flatMap { case (id, (h, tx, accs)) => accs.map(acc => acc -> Set((h, tx.timestamp, id))) }
    val groupedByAcc = map.foldLeft(Map.empty[Address, Set[(Int, Long, ByteStr)]]) { case (m, (acc, set)) =>
      m.combine(Map(acc -> set))
    }
    groupedByAcc
      .mapValues(l => l.toList.sortBy { case ((h, t, _)) => (-h, -t) }) // fresh head ([h=2, h=1, h=0])
      .mapValues(_.map(_._3))
  }
}

object Diff {
  def apply(height: Int, tx: Transaction,
            portfolios: Map[Address, Portfolio] = Map.empty,
            assetInfos: Map[ByteStr, AssetInfo] = Map.empty,
            aliases: Map[Alias, Address] = Map.empty,
            orderFills: Map[ByteStr, VolumeAndFee] = Map.empty,
            paymentTransactionIdsByHashes: Map[ByteStr, ByteStr] = Map.empty,
            leaseState: Map[ByteStr, Boolean] = Map.empty): Diff = Diff(
    transactions = Map((tx.id(), (height, tx, portfolios.keys.toSet))),
    portfolios = portfolios,
    issuedAssets = assetInfos,
    aliases = aliases,
    paymentTransactionIdsByHashes = paymentTransactionIdsByHashes,
    orderFills = orderFills,
    leaseState = leaseState)

  val empty = new Diff(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)

  implicit val diffMonoid = new Monoid[Diff] {
    override def empty: Diff = Diff.empty

    override def combine(older: Diff, newer: Diff): Diff = Diff(
      transactions = older.transactions ++ newer.transactions,
      portfolios = older.portfolios.combine(newer.portfolios),
      issuedAssets = older.issuedAssets.combine(newer.issuedAssets),
      aliases = older.aliases ++ newer.aliases,
      paymentTransactionIdsByHashes = older.paymentTransactionIdsByHashes ++ newer.paymentTransactionIdsByHashes,
      orderFills = older.orderFills.combine(newer.orderFills),
      leaseState = older.leaseState ++ newer.leaseState)
  }
}
