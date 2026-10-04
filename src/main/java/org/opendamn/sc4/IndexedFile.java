package org.opendamn.sc4;

import java.nio.file.Path;
import org.opendamn.dbpf.Tgi;

/**
 * Summary of one DBPF file in the plugin index: the resources relevant for menus and a few
 * facts about each exemplar so that lots can be found without re-reading the file.
 *
 * @param order   global load order (higher loads later and wins)
 */
public record IndexedFile(Path path, long size, long modified, int order, Res[] resources) {

    /**
     * A resource. For exemplars/cohorts {@code exType} is the Exemplar Type (or -1),
     * {@code parent} the parent cohort and {@code buildings} the building instance IDs
     * referenced by a lot configuration.
     */
    public record Res(Tgi tgi, long offset, int size, int exType, Tgi parent, int[] buildings, int flags) {
        public static final int F_HAS_LOT_RESOURCE_KEY = 1;
        public static final int F_HAS_ITEM_BUTTON_ID = 2;
        public static final int F_HAS_ITEM_ICON = 4;
        public static final int F_PLOPPED_ZONE = 8;

        public boolean has(int f) {
            return (flags & f) != 0;
        }
    }
}
