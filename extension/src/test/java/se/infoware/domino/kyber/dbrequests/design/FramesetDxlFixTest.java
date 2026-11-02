package se.infoware.domino.kyber.dbrequests.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hcl.domino.design.FramesetLayout.FrameSizeUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class FramesetDxlFixTest {

  @Test
  void parsePixelRelativeAndPercent() {
    assertEquals(
        List.of(
            new FramesetDxlFix.Size(239, FrameSizeUnit.PIXELS),
            new FramesetDxlFix.Size(1, FrameSizeUnit.RELATIVE)),
        FramesetDxlFix.parseSizes("239px 1"));
    assertEquals(
        List.of(
            new FramesetDxlFix.Size(46, FrameSizeUnit.PIXELS),
            new FramesetDxlFix.Size(1, FrameSizeUnit.RELATIVE),
            new FramesetDxlFix.Size(18, FrameSizeUnit.PIXELS)),
        FramesetDxlFix.parseSizes("46px 1 18px"));
    assertEquals(
        List.of(
            new FramesetDxlFix.Size(1, FrameSizeUnit.RELATIVE),
            new FramesetDxlFix.Size(0, FrameSizeUnit.PERCENTAGE)),
        FramesetDxlFix.parseSizes("1 0%"));
  }

  @Test
  void sizeListsFollowFramesetOpenTags() {
    String dxl =
        """
        <frameset name='$MainFrameset' columns='239px 1'>
        <frameset rows='46px 1 18px'></frameset>
        <frameset rows='1 0%'></frameset>
        </frameset>
        """;
    List<FramesetDxlFix.SizeList> lists = FramesetDxlFix.sizeListsFromDxl(dxl);
    assertEquals(3, lists.size());
    assertTrue(lists.get(0).columns());
    assertEquals(239, lists.get(0).sizes().get(0).value());
    assertEquals(FrameSizeUnit.PIXELS, lists.get(0).sizes().get(0).unit());
    assertFalse(lists.get(1).columns());
    assertEquals(46, lists.get(1).sizes().get(0).value());
    assertEquals(0, lists.get(2).sizes().get(1).value());
    assertEquals(FrameSizeUnit.PERCENTAGE, lists.get(2).sizes().get(1).unit());
  }

  @Test
  void prepareIncomingExpandsEmptySelfClosingFrameset() {
    String dxl =
        """
        <frameset name='JournalFrameset' columns='200px 1'>
          <frameset rows='53px 45 0px'>
            <frameset columns='1 8px' spacing='0px'/>
            <frame name='a'/>
            <frame name='b'/>
          </frameset>
          <frameset rows='1 0%'>
            <frame/>
            <frame name='preview'/>
          </frameset>
        </frameset>
        """;
    String fixed = FramesetDxlFix.prepareIncomingDxl(dxl);
    assertFalse(fixed.contains("<frameset columns='1 8px' spacing='0px'/>"));
    assertTrue(fixed.contains("<frameset columns='1 8px' spacing='0px'><frame/><frame/></frameset>"));
    // Already-populated framesets unchanged in structure (still have named frames).
    assertTrue(fixed.contains("name='a'"));
    assertTrue(fixed.contains("name='preview'"));
  }

  @Test
  void sizeListsIncludeDeeplyNestedFramesets() {
    // Notebook JournalFrameset / BlogFrameset shape: four <frameset> opens in document order.
    String dxl =
        """
        <frameset name='JournalFrameset' columns='200px 1'>
          <frameset rows='53px 45 0px'></frameset>
          <frameset columns='1 8px'>
            <frameset rows='1 0%'></frameset>
          </frameset>
        </frameset>
        """;
    List<FramesetDxlFix.SizeList> lists = FramesetDxlFix.sizeListsFromDxl(dxl);
    assertEquals(4, lists.size());
    assertTrue(lists.get(0).columns());
    assertEquals(List.of(
            new FramesetDxlFix.Size(200, FrameSizeUnit.PIXELS),
            new FramesetDxlFix.Size(1, FrameSizeUnit.RELATIVE)),
        lists.get(0).sizes());
    assertFalse(lists.get(1).columns());
    assertEquals(List.of(
            new FramesetDxlFix.Size(53, FrameSizeUnit.PIXELS),
            new FramesetDxlFix.Size(45, FrameSizeUnit.RELATIVE),
            new FramesetDxlFix.Size(0, FrameSizeUnit.PIXELS)),
        lists.get(1).sizes());
    assertTrue(lists.get(2).columns());
    assertEquals(List.of(
            new FramesetDxlFix.Size(1, FrameSizeUnit.RELATIVE),
            new FramesetDxlFix.Size(8, FrameSizeUnit.PIXELS)),
        lists.get(2).sizes());
    assertFalse(lists.get(3).columns());
    assertEquals(List.of(
            new FramesetDxlFix.Size(1, FrameSizeUnit.RELATIVE),
            new FramesetDxlFix.Size(0, FrameSizeUnit.PERCENTAGE)),
        lists.get(3).sizes());
  }
}
