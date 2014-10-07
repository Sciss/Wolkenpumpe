package de.sciss.nuages;

import javax.swing.*;
import java.awt.*;

/** Work-around a generics incompatibility - cf. http://www.scala-lang.org/old/node/10687 */
abstract class CellRendererFix extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        return getRendererFixed(value, index, isSelected, cellHasFocus);
    }

    protected abstract Component getRendererFixed(Object value, int index, boolean isSelected, boolean cellHasFocus);
}
