package org.opendamn.sc4;

import java.nio.file.Path;
import java.util.List;

/** Manual benchmark: java ... ScanBench <cache> <roots...> */
public class ScanBench {
    public static void main(String[] a) throws Exception {
        Path cache = Path.of(a[0]);
        List<Path> roots = java.util.Arrays.stream(a).skip(1).map(Path::of).toList();
        for (int run = 0; run < 2; run++) {
            long t = System.nanoTime();
            PluginIndex idx = PluginIndex.build(roots, cache, (d, n, p) -> {});
            long ms = (System.nanoTime() - t) / 1_000_000;
            long res = idx.files().stream().mapToLong(f -> f.resources().length).sum();
            long lots = idx.files().stream().flatMap(f -> java.util.Arrays.stream(f.resources()))
                    .filter(r -> r.exType() == 0x10).count();
            long bld = idx.files().stream().flatMap(f -> java.util.Arrays.stream(f.resources()))
                    .filter(r -> r.exType() == 2).count();
            long unk = idx.files().stream().flatMap(f -> java.util.Arrays.stream(f.resources()))
                    .filter(r -> r.exType() == -1 && r.tgi().type() == 0x6534284A).count();
            System.out.printf("run %d: %d ms, files %d, resources %d, lotconfigs %d, buildings %d, untyped %d, errors %d%n",
                    run, ms, idx.files().size(), res, lots, bld, unk, idx.errors().size());
            idx.errors().stream().limit(5).forEach(System.out::println);
        }
    }
}
