package org.opencb.opencga.storage.hadoop.variant.score;

import com.google.common.base.Throwables;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hbase.client.Put;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.VariantScore;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.run.ParallelTaskRunner;
import org.opencb.commons.run.ParallelTaskRunner.Config;
import org.opencb.commons.run.Task;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.io.managers.IOConnector;
import org.opencb.opencga.storage.core.io.plain.StringDataReader;
import org.opencb.opencga.storage.core.metadata.models.VariantScoreMetadata;
import org.opencb.opencga.storage.core.variant.score.VariantScoreFormatDescriptor;
import org.opencb.opencga.storage.core.variant.score.VariantScoreLoader;
import org.opencb.opencga.storage.hadoop.utils.HBaseDataWriter;
import org.opencb.opencga.storage.hadoop.variant.adaptors.VariantHadoopDBAdaptor;
import org.opencb.opencga.storage.hadoop.variant.adaptors.phoenix.PhoenixHelper;
import org.opencb.opencga.storage.hadoop.variant.adaptors.phoenix.VariantPhoenixHelper;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

public class HadoopVariantScoreLoader extends VariantScoreLoader {

    private final VariantHadoopDBAdaptor dbAdaptor;

    public HadoopVariantScoreLoader(VariantHadoopDBAdaptor dbAdaptor, IOConnector ioConnector) {
        super(dbAdaptor.getMetadataManager(), ioConnector);
        this.dbAdaptor = dbAdaptor;
    }

    @Override
    protected void load(URI scoreFile, VariantScoreMetadata scoreMetadata, VariantScoreFormatDescriptor descriptor, ObjectMap options)
            throws ExecutionException, IOException {
        StringDataReader stringReader = getDataReader(scoreFile);

        Task<String, Pair<Variant, VariantScore>> parser = getParser(scoreMetadata, descriptor);
        VariantScoreToHBaseConverter converter = new VariantScoreToHBaseConverter(
                dbAdaptor.getGenomeHelper().getColumnFamily(),
                scoreMetadata.getStudyId(),
                scoreMetadata.getId());

        HBaseDataWriter<Put> hbaseWriter = new HBaseDataWriter<>(dbAdaptor.getHBaseManager(), dbAdaptor.getVariantTable());

        int numTasks = 4;
        ParallelTaskRunner<String, Put> ptr = new ParallelTaskRunner<>(
                stringReader,
                parser.then(converter),
                hbaseWriter,
                Config.builder().setBatchSize(100).setNumTasks(numTasks).build());

        ptr.run();
    }

    @Override
    protected VariantScoreMetadata postLoad(VariantScoreMetadata scoreMetadata, boolean success) throws StorageEngineException {
        if (success) {
            PhoenixHelper.Column column = VariantPhoenixHelper.getVariantScoreColumn(scoreMetadata.getStudyId(), scoreMetadata.getId());
            try {
                Connection connection = dbAdaptor.getJdbcConnection();
                String variantTable = dbAdaptor.getVariantTable();

                PhoenixHelper phoenixHelper = new PhoenixHelper(dbAdaptor.getConfiguration());
                phoenixHelper.addMissingColumns(connection, variantTable, Collections.singleton(column),
                        true, VariantPhoenixHelper.DEFAULT_TABLE_TYPE);
            } catch (SQLException e) {
                throw Throwables.propagate(e);
            }
        }

        return super.postLoad(scoreMetadata, success);
    }
}
