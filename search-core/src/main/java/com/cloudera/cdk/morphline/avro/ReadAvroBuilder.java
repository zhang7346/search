/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.morphline.avro;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import org.apache.avro.generic.GenericContainer;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.Configs;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.MorphlineParsingException;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.base.Fields;
import com.cloudera.cdk.morphline.parser.AbstractParser;
import com.google.common.base.Preconditions;
import com.typesafe.config.Config;


/**
 * Command that parses an InputStream that contains Avro data; for each Avro datum, the command
 * emits a morphline record containing the datum as an attachment in {@link Fields#ATTACHMENT_BODY}.
 * 
 * The Avro schema for reading must be explicitly supplied.
 */
public final class ReadAvroBuilder implements CommandBuilder {

  /** The MIME type identifier that will be filled into output records */
  public static final String AVRO_MEMORY_MIME_TYPE = "avro/java+memory";

  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("readAvro");
  }
  
  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new ReadAvro(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  static class ReadAvro extends AbstractParser {

    private final Schema schema;
    private final boolean isJson;
    
    public ReadAvro(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
      String schemaString = Configs.getString(config, "schemaString", null);
      if (schemaString != null) {
        this.schema = new Parser().parse(schemaString);
      } else {        
        String schemaFile = Configs.getString(config, "schemaFile", null);
        if (schemaFile != null) {
          try { 
            this.schema = new Parser().parse(new File(schemaFile));
          } catch (IOException e) {
            throw new MorphlineParsingException("Cannot parse Avro schema file: " + schemaFile, config, e);
          }
        } else {
          this.schema = null;
        }
      }
      this.isJson = Configs.getBoolean(config, "isJson", false);
    }
    
    /** Returns the Avro schema to use for reading */
    protected Schema getSchema(Schema dataFileReaderSchema) {
      if (this.schema != null) {
        return this.schema;
      }
      return dataFileReaderSchema;
    }

    protected boolean isJSON() {
      return isJson;
    }

    @Override
    protected boolean process(Record inputRecord, InputStream in) throws IOException {
      Schema schema = getSchema(null);
      Preconditions.checkNotNull(schema, "Avro schema must not be null");
      
      DatumReader<GenericContainer> datumReader = new GenericDatumReader<GenericContainer>(schema);
      
      Decoder decoder;
      if (isJSON()) {
        decoder = DecoderFactory.get().jsonDecoder(schema, in);
      } else {
        decoder = DecoderFactory.get().binaryDecoder(in, null);
      }
            
      try {
        GenericContainer datum = new GenericData.Record(schema);
        while (true) {
          datum = datumReader.read(datum, decoder);
          if (!extract(datum, inputRecord)) {
            return false;
          }
        }
      } catch (EOFException e) { 
        ; // ignore
      } finally {
        in.close();
      }
      return true;
    }

    protected boolean extract(GenericContainer datum, Record inputRecord) {
      Record outputRecord = inputRecord.copy();
      removeAttachments(outputRecord);
      outputRecord.put(Fields.ATTACHMENT_BODY, datum);
      outputRecord.put(Fields.ATTACHMENT_MIME_TYPE, ReadAvroBuilder.AVRO_MEMORY_MIME_TYPE);
        
      // pass record to next command in chain:
      return getChild().process(outputRecord);
    }
    
  }
  
}