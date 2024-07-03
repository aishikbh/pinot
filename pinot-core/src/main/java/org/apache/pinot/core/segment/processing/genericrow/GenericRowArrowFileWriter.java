package org.apache.pinot.core.segment.processing.genericrow;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.readers.GenericRow;


public class GenericRowArrowFileWriter implements FileWriter<GenericRow>, Closeable {
  // set allocation size in bytes as 1GB
  private static final int DEFAULT_DIRECT_MEMORY_ALLOCATION_SIZE_IN_BYTES = 1024 * 1024 * 1024;
  private static final int DEFAULT_BATCH_SIZE_BYTES = 512 * 1024 * 1024;
  private final String _filePrefix;
  private final String _fileSuffix = ".arrow";
  private int _suffixCount = 0;
  private final org.apache.arrow.vector.types.pojo.Schema _arrowSchema;
  private int _batchRowCount;
  RootAllocator _rootAllocator;
  VectorSchemaRoot _vectorSchemaRoot;
  long _currentBufferSize;
  int _totalNumRows;
  List<Integer> _chunkRowCounts;
  int _currentChunkRowCount;
  List<FieldSpec> _fieldSpecs;
  File _outputDir;

  public GenericRowArrowFileWriter(File dataOutputDir, List<FieldSpec> fieldSpecs) {
    _filePrefix = "arrowDataFile";
    _arrowSchema = getArrowSchemaFromPinotSchema(fieldSpecs);
    _batchRowCount = 0;
    _currentBufferSize = 0;
    _rootAllocator = new RootAllocator(DEFAULT_DIRECT_MEMORY_ALLOCATION_SIZE_IN_BYTES);
    _vectorSchemaRoot = VectorSchemaRoot.create(_arrowSchema, _rootAllocator);
    _totalNumRows = 0;
    _chunkRowCounts = new ArrayList<>();
    _fieldSpecs = fieldSpecs;
    _currentChunkRowCount = 0;
    _outputDir = dataOutputDir;
  }


  public static org.apache.arrow.vector.types.pojo.Schema getArrowSchemaFromPinotSchema(List<FieldSpec> fieldSpecs) {
    List<Field> arrowFields = new ArrayList<>();

    for (FieldSpec fieldSpec : fieldSpecs) {
      FieldSpec.DataType storedType = fieldSpec.getDataType().getStoredType();
      FieldType fieldType;
      org.apache.arrow.vector.types.pojo.Field arrowField;
      if (fieldSpec.isSingleValueField()) {
        switch (storedType) {
          case INT:
            fieldType = FieldType.nullable(new ArrowType.Int(32, true));
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case LONG:
            fieldType = FieldType.nullable(new ArrowType.Int(64, true));
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case FLOAT:
            fieldType = FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE));
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case DOUBLE:
            fieldType = FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case STRING:
            fieldType = FieldType.nullable(new ArrowType.Utf8());
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case BYTES:
            fieldType = FieldType.nullable(new ArrowType.Binary());
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          default:
            throw new RuntimeException("Unsupported data type: " + storedType);
        }
      } else {
        switch (storedType) {
          case INT:
            fieldType = new FieldType(true, new ArrowType.List.Int(32, true), null);
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case LONG:
            fieldType = new FieldType(true, new ArrowType.List.Int(64, true), null);
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case FLOAT:
            fieldType = new FieldType(true, new ArrowType.List.FloatingPoint(FloatingPointPrecision.SINGLE), null);
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case DOUBLE:
            fieldType = new FieldType(true, new ArrowType.List.FloatingPoint(FloatingPointPrecision.DOUBLE), null);
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          case STRING:
            fieldType = new FieldType(true, new ArrowType.List.Utf8(), null);
            arrowField = new org.apache.arrow.vector.types.pojo.Field(fieldSpec.getName(), fieldType, null);
            arrowFields.add(arrowField);
            break;
          default:
            throw new RuntimeException("Unsupported data type: " + storedType);
        }
      }
    }
    return new org.apache.arrow.vector.types.pojo.Schema(arrowFields);
  }

  private void fillVectorsFromGenericRow(GenericRow row) {
    List<FieldVector> fieldVectors = _vectorSchemaRoot.getFieldVectors();
    int count = 0;
    for (FieldSpec fieldSpec : _fieldSpecs) {
      FieldVector fieldVector = fieldVectors.get(count++);
      byte[] bytes;
      if (fieldSpec.isSingleValueField()) {
        switch (fieldSpec.getDataType().getStoredType()) {
          case INT:
            ((IntVector) fieldVector).setSafe(_batchRowCount, (Integer) row.getValue(fieldSpec.getName()));
            break;
          case LONG:
            ((BigIntVector) fieldVector).setSafe(_batchRowCount, (Long) row.getValue(fieldSpec.getName()));
            break;
          case FLOAT:
            ((Float4Vector) fieldVector).setSafe(_batchRowCount, (Float) row.getValue(fieldSpec.getName()));
            break;
          case DOUBLE:
            ((Float8Vector) fieldVector).setSafe(_batchRowCount, (Double) row.getValue(fieldSpec.getName()));
            break;
          case STRING:
            bytes = row.getValue(fieldSpec.getName()).toString().getBytes();
            ((VarCharVector) fieldVector).setSafe(_batchRowCount, bytes, 0, bytes.length);
            break;
          case BYTES:
            bytes = (byte[]) row.getValue(fieldSpec.getName());
            ((VarBinaryVector) fieldVector).setSafe(_batchRowCount, bytes, 0, bytes.length);
            break;
          default:
            throw new RuntimeException("Unsupported data type: " + fieldSpec.getDataType().getStoredType());
        }
      } else {
        Object[] values = (Object[]) row.getValue(fieldSpec.getName());
        int numValues = values.length;
        switch (fieldSpec.getDataType().getStoredType()) {
          case INT:
            UnionListWriter listWriter = ((ListVector) fieldVector).getWriter();
            listWriter.setPosition(_batchRowCount);
            listWriter.startList();
            for (Object value : values) {
              listWriter.writeInt((Integer) value);
            }
            listWriter.setValueCount(numValues);
            listWriter.endList();
            break;
          case LONG:
            UnionListWriter listWriterLong = ((ListVector) fieldVector).getWriter();
            listWriterLong.setPosition(_batchRowCount);
            listWriterLong.startList();
            for (Object value : values) {
              listWriterLong.writeBigInt((Long) value);
            }
            listWriterLong.setValueCount(numValues);
            listWriterLong.endList();
            break;
          case FLOAT:
            UnionListWriter listWriterFloat = ((ListVector) fieldVector).getWriter();
            listWriterFloat.setPosition(_batchRowCount);
            listWriterFloat.startList();
            for (Object value : values) {
              listWriterFloat.writeFloat4((Float) value);
            }
            listWriterFloat.setValueCount(numValues);
            listWriterFloat.endList();
            break;
          case DOUBLE:
            UnionListWriter listWriterDouble = ((ListVector) fieldVector).getWriter();
            listWriterDouble.setPosition(_batchRowCount);
            listWriterDouble.startList();
            for (Object value : values) {
              listWriterDouble.writeFloat8((Double) value);
            }
            listWriterDouble.setValueCount(numValues);
            listWriterDouble.endList();
            break;
          case STRING:
            UnionListWriter listWriterString = ((ListVector) fieldVector).getWriter();
            listWriterString.setPosition(_batchRowCount);
            listWriterString.startList();
            for (Object value : values) {
              bytes = value.toString().getBytes();
              BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
              ArrowBuf arrowBuf = allocator.buffer(bytes.length);
              arrowBuf.writeBytes(bytes);
              listWriterString.writeVarChar(0, bytes.length, arrowBuf);
            }
            listWriterString.setValueCount(numValues);
            listWriterString.endList();
            break;
          case BYTES:
            UnionListWriter listWriterBytes = ((ListVector) fieldVector).getWriter();
            listWriterBytes.setPosition(_batchRowCount);
            listWriterBytes.startList();
            for (Object value : values) {
              bytes = (byte[]) value;
              BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
              ArrowBuf arrowBuf = allocator.buffer(bytes.length);
              arrowBuf.writeBytes(bytes);
              listWriterBytes.writeVarBinary(0, bytes.length, arrowBuf);
            }
            listWriterBytes.setValueCount(numValues);
            listWriterBytes.endList();
            break;
          default:
            throw new RuntimeException("Unsupported data type: " + fieldSpec.getDataType().getStoredType());
        }
      }
    }
    _batchRowCount++;
  }




  public void write(GenericRow row) {
    fillVectorsFromGenericRow(row);
    _vectorSchemaRoot.setRowCount(_batchRowCount);
//    if (_currentBufferSize >= DEFAULT_BATCH_SIZE_BYTES)
//    if (_rootAllocator.getAllocatedMemory() >= DEFAULT_BATCH_SIZE_BYTES) {
    if (_vectorSchemaRoot.getRowCount() >= 1000) {
      writeToFile();
//      String fileName = _filePrefix + (_suffixCount++) + _fileSuffix;
//      File outFile = new File(_outputDir, fileName);
//      String sortedColumnFilePath;
//      File sortedColumnFile;
//      if (_sortColumns != null) {
//        sortAllColumns();
//
//        // Dump the sorted columns in separate files for easier access during reading.
//        sortedColumnFilePath = sortedFilePrefix + (sortedSuffixCount++) + fileSuffix;
//        sortedColumnFile = new File(sortedColumnFilePath);
//        List<FieldVector> sortColumnsList = new ArrayList<>();
//        List<Field> sortFields = new ArrayList<>();
//
//        for (String sortColumn : _sortColumns) {
//          sortColumnsList.add(_vectorSchemaRoot.getVector(sortColumn));
//          sortFields.add(
//              new Field(sortColumn, _vectorSchemaRoot.getVector(sortColumn).getField().getFieldType(), null));
//        }
//        try {
//          VectorSchemaRoot sortedVectorSchemaRoot = new VectorSchemaRoot(sortFields, sortColumnsList);
//          try (FileOutputStream fileOutputStream = new FileOutputStream(sortedColumnFile);
//              ArrowFileWriter writer = new ArrowFileWriter(sortedVectorSchemaRoot, null,
//                  fileOutputStream.getChannel());) {
//            writer.start();
//            writer.writeBatch();
//            writer.end();
//          } catch (Exception e) {
//            throw new RuntimeException("Failed to write Arrow file", e);
//          }
//        } catch (Exception e) {
//          throw new RuntimeException("Failed to create sorted VectorSchemaRoot", e);
//        }
//      }
//      try (FileOutputStream fileOutputStream = new FileOutputStream(outFile);
//          ArrowFileWriter writer = new ArrowFileWriter(_vectorSchemaRoot, null, fileOutputStream.getChannel());) {
//        writer.start();
//        writer.writeBatch();
//        writer.end();
//      } catch (Exception e) {
//        throw new RuntimeException("Failed to write Arrow file", e);
//      }
//      _vectorSchemaRoot.clear();
//      _currentBufferSize = 0;
//      _batchRowCount = 0;
//      _currentChunkRowCount = 0;
    }
    _currentChunkRowCount++;
    _totalNumRows++;
  }

  public long writeData(GenericRow row) {
    fillVectorsFromGenericRow(row);
    _vectorSchemaRoot.setRowCount(_batchRowCount);
//    if (_currentBufferSize >= DEFAULT_BATCH_SIZE_BYTES)
//    if (_rootAllocator.getAllocatedMemory() >= DEFAULT_BATCH_SIZE_BYTES) {
    if (_vectorSchemaRoot.getRowCount() >= 2) {
      writeToFile();
//      String fileName = _filePrefix + (_suffixCount++) + _fileSuffix;
//      File outFile = new File(_outputDir, fileName);
//      String sortedColumnFilePath;
//      File sortedColumnFile;
//      if (_sortColumns != null) {
//        sortAllColumns();
//
//        // Dump the sorted columns in separate files for easier access during reading.
//        sortedColumnFilePath = sortedFilePrefix + (sortedSuffixCount++) + fileSuffix;
//        sortedColumnFile = new File(sortedColumnFilePath);
//        List<FieldVector> sortColumnsList = new ArrayList<>();
//        List<Field> sortFields = new ArrayList<>();
//
//        for (String sortColumn : _sortColumns) {
//          sortColumnsList.add(_vectorSchemaRoot.getVector(sortColumn));
//          sortFields.add(
//              new Field(sortColumn, _vectorSchemaRoot.getVector(sortColumn).getField().getFieldType(), null));
//        }
//        try {
//          VectorSchemaRoot sortedVectorSchemaRoot = new VectorSchemaRoot(sortFields, sortColumnsList);
//          try (FileOutputStream fileOutputStream = new FileOutputStream(sortedColumnFile);
//              ArrowFileWriter writer = new ArrowFileWriter(sortedVectorSchemaRoot, null,
//                  fileOutputStream.getChannel());) {
//            writer.start();
//            writer.writeBatch();
//            writer.end();
//          } catch (Exception e) {
//            throw new RuntimeException("Failed to write Arrow file", e);
//          }
//        } catch (Exception e) {
//          throw new RuntimeException("Failed to create sorted VectorSchemaRoot", e);
//        }
//      }
//      try (FileOutputStream fileOutputStream = new FileOutputStream(outFile);
//          ArrowFileWriter writer = new ArrowFileWriter(_vectorSchemaRoot, null, fileOutputStream.getChannel());) {
//        writer.start();
//        writer.writeBatch();
//        writer.end();
//      } catch (Exception e) {
//        throw new RuntimeException("Failed to write Arrow file", e);
//      }
//      _vectorSchemaRoot.clear();
//      _currentBufferSize = 0;
//      _batchRowCount = 0;
//      _currentChunkRowCount = 0;
    }
    _currentChunkRowCount++;
    _totalNumRows++;
    return 0;
  }

  public int getTotalNumRows() {
    return _totalNumRows;
  }

  public Schema getArrowSchema() {
    return _arrowSchema;
  }

  public List<Integer> getChunkRowCounts() {
    return _chunkRowCounts;
  }

  private void writeToFile() {
    String fileName = _filePrefix + (_suffixCount++) + _fileSuffix;
    File outFile = new File(_outputDir, fileName);
    try (FileOutputStream fileOutputStream = new FileOutputStream(outFile);
        ArrowFileWriter writer = new ArrowFileWriter(_vectorSchemaRoot, null, fileOutputStream.getChannel());) {
      writer.start();
      writer.writeBatch();
      writer.end();
    } catch (Exception e) {
      throw new RuntimeException("Failed to write Arrow file", e);
    }
    _vectorSchemaRoot.clear();
    _currentBufferSize = 0;
    _batchRowCount = 0;
    _currentChunkRowCount = 0;
//    _currentChunkRowCount++;
//    _totalNumRows++;
  }



  public void close() {
    writeToFile();
  }
}
