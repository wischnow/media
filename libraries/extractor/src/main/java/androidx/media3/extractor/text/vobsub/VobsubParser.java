package androidx.media3.extractor.text.vobsub;

import static java.lang.Math.min;

import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Format.CueReplacementBehavior;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;
import android.graphics.Rect;

// Much of this is taken from or very similar to PgsParser

/** A {@link SubtitleParser} for Vobsub subtitles. */
@UnstableApi
public final class VobsubParser implements SubtitleParser {

  /**
   * The {@link CueReplacementBehavior} for consecutive {@link CuesWithTiming} emitted by this
   * implementation.
   */
  public static final @CueReplacementBehavior int CUE_REPLACEMENT_BEHAVIOR =
      Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;

  private static final int DEFAULT_DURATION = 5000000;

  private final ParsableByteArray buffer;
  private final ParsableByteArray inflatedBuffer;
  private final CueBuilder cueBuilder;
  @Nullable private Inflater inflater;

  public VobsubParser(List<byte[]> initializationData) {

    buffer = new ParsableByteArray();
    inflatedBuffer = new ParsableByteArray();
    cueBuilder = new CueBuilder();
    cueBuilder.parseIdx(new String(initializationData.get(0), StandardCharsets.UTF_8));
  }

  @Override
  public @CueReplacementBehavior int getCueReplacementBehavior() {
    return CUE_REPLACEMENT_BEHAVIOR;
  }

  @Override
  public void parse(
      byte[] data,
      int offset,
      int length,
      OutputOptions outputOptions,
      Consumer<CuesWithTiming> output) {

    buffer.reset(data, offset + length);
    buffer.setPosition(offset);
    if (inflater == null) {
      inflater = new Inflater();
    }
    if (Util.maybeInflate(buffer, inflatedBuffer, inflater)) {
      buffer.reset(inflatedBuffer.getData(), inflatedBuffer.limit());
    }
    cueBuilder.reset();
    Cue cue = null;

    int blen = buffer.bytesLeft();
    if (blen >= 2) {
      int len = buffer.readUnsignedShort();

      if (len == blen) {
        cueBuilder.parseSpu(buffer);
        cue = cueBuilder.build(buffer);
      }
    }
    output.accept(
             new CuesWithTiming(
                 cue != null ? ImmutableList.of(cue) : ImmutableList.of(),
                 /* startTimeUs= */ C.TIME_UNSET,
                 /* durationUs= */ DEFAULT_DURATION));
  }

  private static final class CueBuilder {

    private static final int CMD_COLORS  = 3;
    private static final int CMD_ALPHA   = 4;
    private static final int CMD_AREA    = 5;
    private static final int CMD_OFFSETS = 6;
    private static final int CMD_END     = 255;

    private boolean hasPlane;
    private boolean hasColors;
    private boolean hasDataOffsets;
    private int[] palette;
    private int planeWidth;
    private int planeHeight;
    private int[] colors;
    private Rect boundingBox;
    private int dataOffset0, dataOffset1;
    private int dataSize;

    public CueBuilder() {
      colors = new int[4];
    }

    public void parseIdx(String idx) {
      for (String line : Util.split(idx.trim(), "\\r?\\n")) {
        if (line.startsWith("palette: ")) {
          String[] values = Util.split(line.substring("palette: ".length()), ",");
          palette = new int[values.length];

          for (int i = 0; i < values.length; i++) {
            palette[i] = parseColor(values[i].trim());
          }
        } else if (line.startsWith("size: ")) {

          // NOTE: we need this line to calculate the relative positions
          //       and size required by Cue.Builder() below.

          String[] sizes = Util.split(line.substring("size: ".length()).trim(), "x");

          if (sizes.length == 2) {
            try {
              planeWidth = Integer.parseInt(sizes[0]);
              planeHeight = Integer.parseInt(sizes[1]);
              hasPlane = true;
            } catch (RuntimeException e) {
            }
          }
        }
      }
    }

    private static int parseColor(String value) {
      try {
        return Integer.parseInt(value, 16);
      } catch (RuntimeException e) {
        return 0;
      }
    }

    public void parseSpu(ParsableByteArray buffer) {

      // Give up if we don't have the color palette or the video size.
      // (See also the NOTE above)

      if (palette == null || !hasPlane) return;

      int pos = buffer.getPosition();

      dataSize = buffer.readUnsignedShort();
      pos += dataSize;
      buffer.setPosition(pos);

      int end = buffer.readUnsignedShort();
      parseControl(buffer, end);
    }

    private void parseControl(ParsableByteArray buffer, int end) {

      while (buffer.getPosition() < end && buffer.bytesLeft() > 0) {
        switch (buffer.readUnsignedByte()) {
          case CMD_COLORS:
            if (!parseControlColors(buffer)) return;
            break;

          case CMD_ALPHA:
            if (!parseControlAlpha(buffer)) return;
            break;

          case CMD_AREA:
            if (!parseControlArea(buffer)) return;
            break;

          case CMD_OFFSETS:
            if (!parseControlOffsets(buffer)) return;
            break;

          case CMD_END:
            return;
        }
      }
    }

    private boolean parseControlColors(ParsableByteArray buffer) {
      if (buffer.bytesLeft() < 2) return false;

      int byte0 = buffer.readUnsignedByte();
      int byte1 = buffer.readUnsignedByte();

      colors[3] = getColor(byte0 >> 4);
      colors[2] = getColor(byte0 & 0xf);
      colors[1] = getColor(byte1 >> 4);
      colors[0] = getColor(byte1 & 0xf);
      hasColors = true;

      return true;
    }

    private boolean parseControlAlpha(ParsableByteArray buffer) {
      if (buffer.bytesLeft() < 2) return false;

      int byte0 = buffer.readUnsignedByte();
      int byte1 = buffer.readUnsignedByte();

      colors[3] = setAlpha(colors[3], (byte0 >> 4));
      colors[2] = setAlpha(colors[2], (byte0 & 0xf));
      colors[1] = setAlpha(colors[1], (byte1 >> 4));
      colors[0] = setAlpha(colors[0], (byte1 & 0xf));

      return true;
    }

    private boolean parseControlArea(ParsableByteArray buffer) {
      if (buffer.bytesLeft() < 6) return false;

      int byte0 = buffer.readUnsignedByte();
      int byte1 = buffer.readUnsignedByte();
      int byte2 = buffer.readUnsignedByte();

      int left = (byte0 << 4) | (byte1 >> 4);
      int right = ((byte1 & 0xf) << 8) | byte2;

      int byte3 = buffer.readUnsignedByte();
      int byte4 = buffer.readUnsignedByte();
      int byte5 = buffer.readUnsignedByte();

      int top = (byte3 << 4) | (byte4 >> 4);
      int bottom = ((byte4 & 0xf) << 8) | byte5;

      boundingBox = new Rect(left, top, right + 1, bottom + 1);

      return true;
    }

    private boolean parseControlOffsets(ParsableByteArray buffer) {
      if (buffer.bytesLeft() < 4) return false;

      dataOffset0 = buffer.readUnsignedShort();
      dataOffset1 = buffer.readUnsignedShort();
      hasDataOffsets = true;

      return true;
    }

    private int getColor(int index) {
      if (index >= 0 && index < palette.length) return palette[index];
      return palette[0];
    }

    private int setAlpha(int color, int alpha) {
      return ((color & 0x00ffffff) | ((alpha * 17) << 24));
    }

    public Cue build(ParsableByteArray buffer) {
      if (palette == null
          || !hasPlane
          || !hasColors
          || boundingBox == null
          || !hasDataOffsets
          || boundingBox.width() < 2
          || boundingBox.height() < 2) {
        return null;
      }
      int[] bitmapData = new int[boundingBox.width() * boundingBox.height()];
      ParsableBitArray bitBuffer = new ParsableBitArray();

      buffer.setPosition(dataOffset0);
      bitBuffer.reset(buffer);
      parseRleData(bitBuffer, 0, bitmapData);
      buffer.setPosition(dataOffset1);
      bitBuffer.reset(buffer);
      parseRleData(bitBuffer, 1, bitmapData);

      Bitmap bitmap = Bitmap.createBitmap(bitmapData, boundingBox.width(), boundingBox.height(), Bitmap.Config.ARGB_8888);

      return new Cue.Builder()
          .setBitmap(bitmap)
          .setPosition((float) boundingBox.left / planeWidth)
          .setPositionAnchor(Cue.ANCHOR_TYPE_START)
          .setLine((float) boundingBox.top / planeHeight, Cue.LINE_TYPE_FRACTION)
          .setLineAnchor(Cue.ANCHOR_TYPE_START)
          .setSize((float) boundingBox.width() / planeWidth)
          .setBitmapHeight((float) boundingBox.height() / planeHeight)
          .build();
    }

    /**
     * Parse run-length encoded data into the {@code bitmapData} array. The
     * subtitle bitmap is encoded in two blocks of interlaced lines, {@code y}
     * gives the index of the starting line (0 or 1).
     *
     * @param bitBuffer The RLE encoded data.
     * @param y Index of the first line.
     * @param bitmapData Output array.
     */
      private void parseRleData(ParsableBitArray bitBuffer, int y, int[] bitmapData) {
      int width = boundingBox.width();
      int height = boundingBox.height();
      int x = 0;
      int outIndex = y * width;
      Run run = new Run();

      while (true) {
        parseRun(bitBuffer, run);

        int length = min(run.length, width - x);

        if (length > 0) {
            Arrays.fill(bitmapData, outIndex, outIndex + length, run.color);
            outIndex += length;
            x += length;
        }
        if (x >= width) {
          y += 2;
          if (y >= height) break;
          x = 0;
          outIndex = y * width;
          bitBuffer.byteAlign();
        }
      }
    }

    private void parseRun(ParsableBitArray bitBuffer, Run run) {
      int value = 0;
      int test = 1;

      while (value < test && test <= 0x40) {
        if (bitBuffer.bitsLeft() < 4) {
          run.color = 0;
          run.length = 0;
          return;
        }
        value = (value << 4) | bitBuffer.readBits(4);
        test <<= 2;
      }
      run.color = colors[value & 3];
      run.length = value < 4 ? boundingBox.width() : (value >> 2);
    }

    public void reset() {
      hasColors = false;
      boundingBox = null;
      hasDataOffsets = false;
    }

    private class Run {
      public int color;
      public int length;
    }
  }
}