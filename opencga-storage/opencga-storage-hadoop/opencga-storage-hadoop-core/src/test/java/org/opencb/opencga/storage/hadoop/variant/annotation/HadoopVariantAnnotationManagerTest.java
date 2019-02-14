package org.opencb.opencga.storage.hadoop.variant.annotation;

import org.junit.After;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;
import org.opencb.opencga.storage.core.variant.annotation.VariantAnnotationManagerTest;
import org.opencb.opencga.storage.hadoop.variant.HadoopVariantStorageEngine;
import org.opencb.opencga.storage.hadoop.variant.HadoopVariantStorageTest;
import org.opencb.opencga.storage.hadoop.variant.VariantHbaseTestUtils;
import org.opencb.opencga.storage.hadoop.variant.annotation.pending.DiscoverPendingVariantsToAnnotateDriver;
import org.opencb.opencga.storage.hadoop.variant.annotation.pending.PendingVariantsToAnnotateReader;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created on 25/04/18.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class HadoopVariantAnnotationManagerTest extends VariantAnnotationManagerTest implements HadoopVariantStorageTest {

    @ClassRule
    public static ExternalResource externalResource = new HadoopExternalResource();

    @After
    public void tearDown() throws Exception {
        VariantHbaseTestUtils.printVariants(((HadoopVariantStorageEngine) variantStorageEngine).getDBAdaptor(), newOutputUri(getTestName().getMethodName()));
    }

    @Test
    public void incrementalAnnotationTest() throws Exception {
        HadoopVariantStorageEngine engine = getVariantStorageEngine();
        for (int i = 0; i < 3; i++) {
            URI platinumFile = getPlatinumFile(i);

            runDefaultETL(platinumFile, engine, null, new ObjectMap(VariantStorageEngine.Options.ANNOTATE.key(), false)
                    .append(VariantStorageEngine.Options.CALCULATE_STATS.key(), false));

            // Update pending variants
            new TestMRExecutor().run(DiscoverPendingVariantsToAnnotateDriver.class,
                    DiscoverPendingVariantsToAnnotateDriver.buildArgs(engine.getDBAdaptor().getVariantTable(), new ObjectMap()),
                    new ObjectMap(), "Prepare variants to annotate");

            long pendingVariantsCount = new PendingVariantsToAnnotateReader(engine.getDBAdaptor(), new Query()).stream().count();
            System.out.println("pendingVariants = " + pendingVariantsCount);
            long expectedPendingVariantsCount = engine.count(new Query(VariantQueryParam.ANNOTATION_EXISTS.key(), false)).first();
            Assert.assertEquals(expectedPendingVariantsCount, pendingVariantsCount);
            engine.annotate(new Query(), new ObjectMap());


            List<Variant> pendingVariants = new PendingVariantsToAnnotateReader(engine.getDBAdaptor(), new Query())
                    .stream()
                    .collect(Collectors.toList());
            expectedPendingVariantsCount = engine.count(new Query(VariantQueryParam.ANNOTATION_EXISTS.key(), false)).first();
            Assert.assertEquals(0, expectedPendingVariantsCount);
            Assert.assertEquals(pendingVariants.toString(), 0, pendingVariants.size());
            Assert.assertNotEquals(0, engine.count(new Query()).first().longValue());
        }

    }
}
