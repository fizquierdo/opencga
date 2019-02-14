package org.opencb.opencga.storage.hadoop.variant.annotation;

import org.apache.hadoop.hbase.client.Put;
import org.opencb.opencga.storage.hadoop.utils.HBaseDataWriter;
import org.opencb.opencga.storage.hadoop.utils.HBaseManager;
import org.opencb.opencga.storage.hadoop.variant.annotation.pending.PendingVariantsToAnnotateDBCleaner;
import org.opencb.opencga.storage.hadoop.variant.search.HadoopVariantSearchIndexUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Created on 19/04/18.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class VariantAnnotationHadoopDBWriter extends HBaseDataWriter<Put> {

    private static final int PENDING_VARIANTS_BUFFER_SIZE = 100_000;

    private byte[] columnFamily;
    private List<byte[]> loadedVariants = new ArrayList<>(PENDING_VARIANTS_BUFFER_SIZE);
    private final PendingVariantsToAnnotateDBCleaner pendingVariantsCleaner;

    public VariantAnnotationHadoopDBWriter(HBaseManager hBaseManager, String tableName, byte[] columnFamily) {
        super(hBaseManager, tableName);
        this.columnFamily = columnFamily;

        pendingVariantsCleaner = new PendingVariantsToAnnotateDBCleaner(hBaseManager, tableName);
    }

    @Override
    public boolean open() {
        super.open();
        return pendingVariantsCleaner.open();
    }

    @Override
    public boolean pre() {
        super.pre();
        pendingVariantsCleaner.pre();
        return true;
    }

    @Override
    public boolean post() {
        super.post();
        cleanPendingVariants();
        pendingVariantsCleaner.post();
        return true;
    }

    @Override
    public boolean close() {
        super.close();
        return pendingVariantsCleaner.close();
    }

    @Override
    protected List<Put> convert(List<Put> puts) {
        if (loadedVariants.size() + puts.size() >= PENDING_VARIANTS_BUFFER_SIZE) {
            cleanPendingVariants();
        }
        for (Put put : puts) {
            HadoopVariantSearchIndexUtils.addNotSyncStatus(put, columnFamily);
            loadedVariants.add(put.getRow());
        }

        return puts;
    }

    private void cleanPendingVariants() {
        System.out.println("loadedVariants.size() = " + loadedVariants.size());
        flush(); // Ensure own BufferedMutator is flushed
        pendingVariantsCleaner.write(loadedVariants);
        loadedVariants.clear();
    }
}
