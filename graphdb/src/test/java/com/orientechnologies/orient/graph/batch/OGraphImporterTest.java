package com.orientechnologies.orient.graph.batch;

import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.graph.importer.OGraphImporter;
import junit.framework.TestCase;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * @author Luca Garulli
 */

public class OGraphImporterTest extends TestCase {

  @Test
  public void test1() throws IOException, InterruptedException {
    String dbUrl = "memory:liveJournal";
    OGraphImporter batch = new OGraphImporter(dbUrl, "admin", "admin");

    batch.registerVertexClass("User", "id", OType.INTEGER);
    batch.registerEdgeClass("Friend", "User", "User");

    batch.begin();

    File file = new File("/Users/luca/Downloads/soc-LiveJournal1.txt");
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      int row = 0;
      for (String line; (line = br.readLine()) != null;) {
        row++;
        if (row < 5)
          continue;

        final int pos = line.indexOf('\t');
        final Integer from = new Integer(line.substring(0, pos));
        final Integer to = new Integer(line.substring(pos + 1));

        batch.createEdge("Friend", "User", from, "User", to, null);
      }
    }

    batch.end();
  }
}
