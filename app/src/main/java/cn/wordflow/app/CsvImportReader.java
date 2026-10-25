package cn.wordflow.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Android document access and bounded UTF-8 decoding, separate from import/merge policy. */
final class CsvImportReader {
  private final ContentResolver resolver;
  private long batchBytes;

  CsvImportReader(ContentResolver resolver) {
    this.resolver = resolver;
  }

  WordStore.Deck read(Uri uri) throws Exception {
    String name = "导入的词表";
    try (Cursor c =
        resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
    }
    name = name.replaceFirst("(?i)\\.csv$", "").trim();
    if (name.isEmpty()) name = "导入的词表";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (InputStream in = resolver.openInputStream(uri)) {
      if (in == null) throw new IOException("无法打开文件");
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        batchBytes += n;
        if (batchBytes > 20 * 1024 * 1024) throw new IOException("一批文件合计不能超过 20 MB，请分批导入同一词表");
        if (bytes.size() + n > 5 * 1024 * 1024) throw new IOException("每个文件不能超过 5 MB");
        bytes.write(buffer, 0, n);
      }
    }
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString();
    return new WordStore.Deck(name, CsvParser.parse(text));
  }
}
