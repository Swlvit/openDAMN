package org.opendamn.dbpf;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbpfRoundTripTest {

    /** Optional real-world sample folder (read only). Set with -Dopendamn.samples=... */
    static Path samples() {
        String p = System.getProperty("opendamn.samples", System.getenv().getOrDefault("OPENDAMN_SAMPLES", ""));
        return p.isEmpty() ? null : Path.of(p);
    }

    @Test
    void qfsRoundTripsSyntheticData() {
        Random r = new Random(42);
        for (int len : new int[] {100, 1000, 70_000, 300_000}) {
            byte[] d = new byte[len];
            // Mix of compressible and random content.
            for (int k = 0; k < len; k++) d[k] = (byte) ((k % 97 < 60) ? (k % 13) : r.nextInt(256));
            byte[] c = Qfs.compress(d);
            assertNotNull(c, "len " + len);
            assertTrue(Qfs.isCompressed(c));
            assertArrayEquals(d, Qfs.decompress(c), "len " + len);
        }
    }

    @Test
    void qfsLongRunsAndFarOffsets() {
        byte[] d = new byte[200_000];
        Random r = new Random(7);
        r.nextBytes(d);
        System.arraycopy(d, 0, d, 140_000, 2000);      // too far for any opcode
        System.arraycopy(d, 1000, d, 100_000, 1500);   // 4-byte opcode, long match
        System.arraycopy(d, 50_000, d, 60_000, 50);    // 3-byte opcode
        byte[] c = Qfs.compress(d);
        assertNotNull(c);
        assertArrayEquals(d, Qfs.decompress(c));
    }

    @Test
    void writerReaderRoundTrip(@TempDir Path tmp) throws IOException {
        Exemplar ex = new Exemplar(false, Exemplar.Format.BINARY, new Tgi(0, 0, 0), List.of(
                Property.ofUint32(Exemplar.P_EXEMPLAR_TYPE, 2),
                Property.ofString(Exemplar.P_EXEMPLAR_NAME, "Test, with \"comma\""),
                new Property(Exemplar.P_LOT_CONFIG_SIZE, PropertyType.UINT8, true, new long[] {3, 4}, null),
                new Property(0x12345678, PropertyType.FLOAT32, false,
                        new long[] {Float.floatToRawIntBits(1.5f) & 0xFFFFFFFFL}, null),
                new Property(0x22222222, PropertyType.SINT32, false, new long[] {-5}, null),
                new Property(0x33333333, PropertyType.BOOL, false, new long[] {1}, null)));
        String text = "Hello\nwörld";
        Path f = tmp.resolve("t.dat");
        new DbpfWriter()
                .add(new Tgi(Tgi.TYPE_EXEMPLAR, 1, 2), ex.encode(), true)
                .add(new Tgi(Tgi.TYPE_LTEXT, 3, 4), LText.encode(text))
                .write(f);

        DbpfFile db = DbpfFile.open(f);
        assertEquals(3, db.entries().size(), "exemplar + ltext + directory");
        Exemplar back = Exemplar.parse(db.read(new Tgi(Tgi.TYPE_EXEMPLAR, 1, 2)));
        assertEquals(ex.properties(), back.properties());
        assertEquals(text, LText.decode(db.read(new Tgi(Tgi.TYPE_LTEXT, 3, 4))));

        // Text encoding round trip, including a string with a comma.
        Exemplar viaText = Exemplar.parse(ex.encodeText(id -> "Name"));
        assertEquals(ex.properties(), viaText.properties());
    }

    @Test
    void parsesIlivesTextFormat() {
        String t = "EQZT1###\r\nParentCohort=Key:{0x00000000,0x00000000,0x00000000}\r\nPropCount=0x00000003\r\n"
                + "0x00000010:{\"Exemplar Type\"}=Uint32:0:{0x00000002}\r\n"
                + "0x00000020:{\"Exemplar Name\"}=String:1:{\"A, B = C\"}\r\n"
                + "0xaa1dd396:{\"OccupantGroups\"}=Uint32:2:{0x00001500,0x0000150a}\r\n";
        Exemplar ex = Exemplar.parse(t.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertEquals(2, ex.exemplarType());
        assertEquals("A, B = C", ex.getString(Exemplar.P_EXEMPLAR_NAME));
        assertEquals(0x150a, ex.get(Exemplar.P_OCCUPANT_GROUPS).getLong(1));
    }

    /** Every binary exemplar in the sample set must re-encode byte for byte. */
    @Test
    void realExemplarsReencodeExactly() throws IOException {
        Path root = samples();
        Assumptions.assumeTrue(root != null && Files.isDirectory(root), "no samples configured");
        int files = 0;
        int exemplars = 0;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).limit(400)::iterator) {
                if (!DbpfFile.isDbpf(p)) continue;
                DbpfFile db = DbpfFile.open(p);
                files++;
                try (var rd = db.reader()) {
                    for (DbpfFile.Entry e : db.entries()) {
                        int type = e.tgi().type();
                        byte[] raw = rd.readRaw(e);
                        byte[] d = Qfs.decompressIfNeeded(raw);
                        if (Qfs.isCompressed(raw)) {
                            byte[] again = Qfs.compress(d);
                            if (again != null) assertArrayEquals(d, Qfs.decompress(again), p + " " + e.tgi());
                        }
                        if ((type == Tgi.TYPE_EXEMPLAR || type == Tgi.TYPE_COHORT) && Exemplar.looksLikeExemplar(d)
                                && d[3] == 'B') {
                            Exemplar ex = Exemplar.parse(d);
                            assertArrayEquals(d, ex.encodeBinary(), p + " " + e.tgi());
                            exemplars++;
                        }
                    }
                }
            }
        }
        System.out.println("checked " + files + " files, " + exemplars + " exemplars");
        assertTrue(files > 0);
    }
}
