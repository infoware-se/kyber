package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BinaryDesignResourceTest {

  @Test
  void extractAndReplaceFileData() throws Exception {
    String dxl =
        """
        <?xml version='1.0'?>
        <imageresource name='icon.gif' xmlns='http://www.lotus.com/dxl'>
        <item name='$FileData' summary='false' sign='true'><rawitemdata type='1'>QUJD</rawitemdata></item>
        </imageresource>
        """;
    assertEquals("QUJD", BinaryDesignResource.extractFileData(dxl).replaceAll("\\s+", ""));
    String next = BinaryDesignResource.replaceFileData(dxl, "WFhY");
    assertTrue(next.contains(">WFhY</rawitemdata>"));
    assertEquals("WFhY", BinaryDesignResource.extractFileData(next).replaceAll("\\s+", ""));
  }
}
