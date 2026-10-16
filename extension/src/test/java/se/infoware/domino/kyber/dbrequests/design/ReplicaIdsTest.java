package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ReplicaIdsTest {

  @Test
  void formatStripsTheColonAndUppercases() {
    assertNull(ReplicaIds.format(null));
    assertNull(ReplicaIds.format(""));
    assertNull(ReplicaIds.format("85258123"));
    assertEquals("8525812300456789", ReplicaIds.format("85258123:00456789"));
    assertEquals("8525812300456789", ReplicaIds.format("8525812300456789"));
    assertEquals("0123456789ABCDEF", ReplicaIds.format("0123456789abcdef"));
  }
}
