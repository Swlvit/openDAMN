package org.opendamn.ui;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.List;
import org.opendamn.damn.MenuItem;
import org.opendamn.damn.MenuNode;
import org.opendamn.sc4.LotInfo;

/** Drag-and-drop payloads used inside the application. */
final class Transfers {

    record Lots(List<LotInfo> lots) {}

    record Items(MenuNode source, List<MenuItem> items) {}

    record Menu(MenuNode node) {}

    static final DataFlavor LOTS = flavor(Lots.class);
    static final DataFlavor ITEMS = flavor(Items.class);
    static final DataFlavor MENU = flavor(Menu.class);

    private Transfers() {}

    private static DataFlavor flavor(Class<?> c) {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + c.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    static Transferable of(DataFlavor f, Object payload) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {f};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor x) {
                return f.equals(x);
            }

            @Override
            public Object getTransferData(DataFlavor x) {
                return payload;
            }
        };
    }
}
