package ru.printlab.slicing;

import ru.printlab.order.PrintOrder;
import ru.printlab.storage.ModelFile;

public interface SlicingEngine {
    SliceResult slice(SliceJob job, PrintOrder order, ModelFile model);
}
