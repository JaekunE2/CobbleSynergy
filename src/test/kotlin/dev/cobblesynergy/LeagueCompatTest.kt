package dev.cobblesynergy
import kotlin.test.*
class LeagueCompatTest {
 @Test fun `protocol mapping includes current and legacy releases`() {
  assertEquals(9,LeagueCompat.protocolFor("0.7.1"));assertEquals(9,LeagueCompat.protocolFor("0.7.2"))
  assertEquals(10,LeagueCompat.protocolFor("0.7.3"));assertEquals(11,LeagueCompat.protocolFor("0.7.7"))
  assertEquals(11,LeagueCompat.protocolFor("0.7.10+dev"))
 }
}
