package com.twitter.joauth

trait KeyValueParser extends ((String, Seq[KeyValueHandler]) => Unit)

class ConstKeyValueParser(pairs: List[(String, String)]) extends KeyValueParser {
  def apply(str: String, handlers: Seq[KeyValueHandler]): Unit = {
    pairs.foreach(e => handlers.foreach(_(e._1, e._2)))
  }
}

class StandardKeyValueParser(delimiter: String, kvDelimiter: String) extends KeyValueParser {
  def apply(str: String, handlers: Seq[KeyValueHandler]): Unit = {
    if (str == null || str.length == 0) return
    str.split(delimiter).foreach { kvStr =>
      val kv = kvStr.split(kvDelimiter)
      kv.length match {
        case 2 => handlers.foreach(_(kv(0), kv(1)))
        case 1 => handlers.foreach(_(kv(0), ""))
        case _ =>
      }
    }
  }
}