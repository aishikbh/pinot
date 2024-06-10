package org.apache.pinot.core.segment.processing.genericrow;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
import org.apache.arrow.vector.util.Text;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.Channels;

public class ArrowFileReaderUtil {

  public static void main(String[] args) {
    String arrowFilePath = "/Users/aishik/Work/rawData/output.arrow";

    try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
        FileInputStream fileInputStream = new FileInputStream(arrowFilePath);
        ArrowFileReader reader = new ArrowFileReader(fileInputStream.getChannel(), allocator)) {

      VectorSchemaRoot root = reader.getVectorSchemaRoot();
      reader.loadNextBatch();
      int rowCount = root.getRowCount();

      for (int i = 0; i < rowCount; i++) {
        Text lowCardinalityString = ((VarCharVector) root.getVector("low_cardinality_string")).getObject(i);
        System.out.println("low_cardinality_string: " + (lowCardinalityString != null ? lowCardinalityString.toString() : "null"));
        // Add more fields as needed
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}