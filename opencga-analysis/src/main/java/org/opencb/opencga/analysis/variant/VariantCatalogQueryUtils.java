/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.analysis.variant;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.opencb.biodata.models.clinical.interpretation.ClinicalProperty;
import org.opencb.biodata.models.clinical.interpretation.DiseasePanel;
import org.opencb.biodata.models.clinical.interpretation.DiseasePanel.GenePanel;
import org.opencb.biodata.models.clinical.pedigree.Member;
import org.opencb.biodata.models.clinical.pedigree.Pedigree;
import org.opencb.biodata.models.clinical.pedigree.PedigreeManager;
import org.opencb.biodata.models.commons.Disorder;
import org.opencb.biodata.models.core.Region;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.biodata.tools.pedigree.ModeOfInheritance;
import org.opencb.commons.datastore.core.DataResult;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryParam;
import org.opencb.opencga.catalog.db.api.*;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.*;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.metadata.VariantStorageMetadataManager;
import org.opencb.opencga.storage.core.variant.adaptors.VariantField;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryException;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.opencb.commons.datastore.core.QueryOptions.INCLUDE;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam.*;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils.*;
import static org.opencb.opencga.storage.core.variant.query.CompoundHeterozygousQueryExecutor.MISSING_SAMPLE;

/**
 * Created on 28/02/17.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class VariantCatalogQueryUtils extends CatalogUtils {

    public static final String SAMPLE_ANNOTATION_DESC =
            "Selects some samples using metadata information from Catalog. e.g. age>20;phenotype=hpo:123,hpo:456;name=smith";
    public static final QueryParam SAMPLE_ANNOTATION
            = QueryParam.create("sampleAnnotation", SAMPLE_ANNOTATION_DESC, QueryParam.Type.TEXT_ARRAY);
    public static final String PROJECT_DESC = "Project [user@]project where project can be either the ID or the alias";
    public static final QueryParam PROJECT = QueryParam.create("project", PROJECT_DESC, QueryParam.Type.TEXT_ARRAY);

    public static final String FAMILY_DESC = "Filter variants where any of the samples from the given family contains the variant "
            + "(HET or HOM_ALT)";
    public static final QueryParam FAMILY =
            QueryParam.create("family", FAMILY_DESC, QueryParam.Type.TEXT);
    public static final String FAMILY_MEMBERS_DESC = "Sub set of the members of a given family";
    public static final QueryParam FAMILY_MEMBERS =
            QueryParam.create("familyMembers", FAMILY_MEMBERS_DESC, QueryParam.Type.TEXT);
    public static final String FAMILY_DISORDER_DESC = "Specify the disorder to use for the family segregation";
    public static final QueryParam FAMILY_DISORDER =
            QueryParam.create("familyDisorder", FAMILY_DISORDER_DESC, QueryParam.Type.TEXT);
    public static final String FAMILY_PROBAND_DESC = "Specify the proband child to use for the family segregation";
    public static final QueryParam FAMILY_PROBAND =
            QueryParam.create("familyProband", FAMILY_PROBAND_DESC, QueryParam.Type.TEXT);
    public static final String FAMILY_SEGREGATION_DESCR = "Filter by mode of inheritance from a given family. Accepted values: "
            + "[ monoallelic, monoallelicIncompletePenetrance, biallelic, "
            + "biallelicIncompletePenetrance, XlinkedBiallelic, XlinkedMonoallelic, Ylinked, MendelianError, "
            + "DeNovo, CompoundHeterozygous ]";
    public static final QueryParam FAMILY_SEGREGATION =
            QueryParam.create("familySegregation", FAMILY_SEGREGATION_DESCR, QueryParam.Type.TEXT);

    @Deprecated
    public static final QueryParam FAMILY_PHENOTYPE = FAMILY_DISORDER;
    @Deprecated
    public static final QueryParam MODE_OF_INHERITANCE = FAMILY_SEGREGATION;

    public static final String PANEL_DESC = "Filter by genes from the given disease panel";
    public static final QueryParam PANEL =
            QueryParam.create("panel", PANEL_DESC, QueryParam.Type.TEXT);

    public static final List<QueryParam> VARIANT_CATALOG_QUERY_PARAMS = Arrays.asList(
            SAMPLE_ANNOTATION,
            PROJECT,
            FAMILY,
            FAMILY_MEMBERS,
            FAMILY_DISORDER,
            FAMILY_PROBAND,
            FAMILY_SEGREGATION,
            PANEL);

    private final StudyFilterValidator studyFilterValidator;
    private final FileFilterValidator fileFilterValidator;
    private final SampleFilterValidator sampleFilterValidator;
    private final GenotypeFilterValidator genotypeFilterValidator;
    private final CohortFilterValidator cohortFilterValidator;
    //    public static final QueryParam SAMPLE_FILTER_GENOTYPE = QueryParam.create("sampleFilterGenotype", "", QueryParam.Type.TEXT_ARRAY);
    protected static Logger logger = LoggerFactory.getLogger(VariantCatalogQueryUtils.class);

    public VariantCatalogQueryUtils(CatalogManager catalogManager) {
        super(catalogManager);
        studyFilterValidator = new StudyFilterValidator();
        fileFilterValidator = new FileFilterValidator();
        sampleFilterValidator = new SampleFilterValidator();
        genotypeFilterValidator = new GenotypeFilterValidator();
        cohortFilterValidator = new CohortFilterValidator();
    }

    public static VariantQueryException wrongReleaseException(VariantQueryParam param, String value, int release) {
        return new VariantQueryException("Unable to have '" + value + "' within '" + param.key() + "' filter. "
                + "Not part of release " + release);
    }

    /**
     * Transforms a high level Query to a query fully understandable by storage.
     * @param query     High level query. Will be modified by the method.
     * @param sessionId User's session id
     * @return          Modified input query (same instance)
     * @throws CatalogException if there is any catalog error
     */
    public Query parseQuery(Query query, String sessionId) throws CatalogException {
        if (query == null) {
            // Nothing to do!
            return null;
        }
        List<String> studies = getStudies(query, sessionId);
        String defaultStudyStr = getDefaultStudyId(studies);
        Integer release = getReleaseFilter(query, sessionId);

        studyFilterValidator.processFilter(query, VariantQueryParam.STUDY, release, sessionId, defaultStudyStr);
        studyFilterValidator.processFilter(query, VariantQueryParam.INCLUDE_STUDY, release, sessionId, defaultStudyStr);
        sampleFilterValidator.processFilter(query, VariantQueryParam.SAMPLE, release, sessionId, defaultStudyStr);
        sampleFilterValidator.processFilter(query, VariantQueryParam.INCLUDE_SAMPLE, release, sessionId, defaultStudyStr);
        genotypeFilterValidator.processFilter(query, VariantQueryParam.GENOTYPE, release, sessionId, defaultStudyStr);
        fileFilterValidator.processFilter(query, VariantQueryParam.FILE, release, sessionId, defaultStudyStr);
        fileFilterValidator.processFilter(query, VariantQueryParam.INCLUDE_FILE, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.COHORT, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.STATS_ALT, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.STATS_REF, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.STATS_MAF, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.STATS_MGF, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.MISSING_ALLELES, release, sessionId, defaultStudyStr);
        cohortFilterValidator.processFilter(query, VariantQueryParam.MISSING_GENOTYPES, release, sessionId, defaultStudyStr);

        if (release != null) {
            // If no list of included files is specified:
            if (VariantQueryUtils.isIncludeFilesDefined(query, Collections.singleton(VariantField.STUDIES_FILES))) {
                List<String> includeFiles = new ArrayList<>();
                QueryOptions fileOptions = new QueryOptions(INCLUDE, FileDBAdaptor.QueryParams.UID.key());
                Query fileQuery = new Query(FileDBAdaptor.QueryParams.RELEASE.key(), "<=" + release)
                        .append(FileDBAdaptor.QueryParams.INDEX_STATUS_NAME.key(), FileIndex.IndexStatus.READY);

                for (String study : studies) {
                    for (File file : catalogManager.getFileManager().search(study, fileQuery, fileOptions, sessionId)
                            .getResults()) {
                        includeFiles.add(file.getName());
                    }
                }
                query.append(VariantQueryParam.INCLUDE_FILE.key(), includeFiles);
            }
            // If no list of included samples is specified:
            if (!VariantQueryUtils.isIncludeSamplesDefined(query, Collections.singleton(VariantField.STUDIES_SAMPLES_DATA))) {
                List<String> includeSamples = new ArrayList<>();
                Query sampleQuery = new Query(SampleDBAdaptor.QueryParams.RELEASE.key(), "<=" + release);
                QueryOptions sampleOptions = new QueryOptions(INCLUDE, SampleDBAdaptor.QueryParams.UID.key());

                for (String study : studies) {
                    Query cohortQuery = new Query(CohortDBAdaptor.QueryParams.ID.key(), StudyEntry.DEFAULT_COHORT);
                    QueryOptions cohortOptions = new QueryOptions(INCLUDE, CohortDBAdaptor.QueryParams.SAMPLE_UIDS.key());
                    // Get default cohort. It contains the list of indexed samples. If it doesn't exist, or is empty, do not include any
                    // sample from this study.
                    DataResult<Cohort> result = catalogManager.getCohortManager().search(study, cohortQuery, cohortOptions, sessionId);
                    if (result.first() != null || result.first().getSamples().isEmpty()) {
                        Set<String> sampleIds = result
                                .first()
                                .getSamples()
                                .stream()
                                .map(Sample::getId)
                                .collect(Collectors.toSet());
                        for (Sample s : catalogManager.getSampleManager().search(study, sampleQuery, sampleOptions, sessionId)
                                .getResults()) {
                            if (sampleIds.contains(s.getId())) {
                                includeSamples.add(s.getId());
                            }
                        }
                    }
                }
                query.append(VariantQueryParam.INCLUDE_SAMPLE.key(), includeSamples);
            }
        }

        if (isValidParam(query, SAMPLE_ANNOTATION)) {
            String sampleAnnotation = query.getString(SAMPLE_ANNOTATION.key());
            Query sampleQuery = parseSampleAnnotationQuery(sampleAnnotation, SampleDBAdaptor.QueryParams::getParam);
            sampleQuery.append(SampleDBAdaptor.QueryParams.STUDY_UID.key(), defaultStudyStr);
            QueryOptions options = new QueryOptions(INCLUDE, SampleDBAdaptor.QueryParams.UID);
            List<String> sampleIds = catalogManager.getSampleManager().search(defaultStudyStr, sampleQuery, options, sessionId)
                    .getResults()
                    .stream()
                    .map(Sample::getId)
                    .collect(Collectors.toList());

            if (sampleIds.isEmpty()) {
                throw new VariantQueryException("Could not found samples with this annotation: " + sampleAnnotation);
            }

            String genotype = query.getString("sampleAnnotationGenotype");
//            String genotype = query.getString(VariantDBAdaptor.VariantQueryParams.GENOTYPE.key());
            if (StringUtils.isNotBlank(genotype)) {
                StringBuilder sb = new StringBuilder();
                for (String sampleId : sampleIds) {
                    sb.append(sampleId).append(IS)
                            .append(genotype)
                            .append(AND); // TODO: Should this be an AND (;) or an OR (,)?
                }
                query.append(VariantQueryParam.GENOTYPE.key(), sb.toString());
                if (!isValidParam(query, VariantQueryParam.INCLUDE_SAMPLE)) {
                    query.append(VariantQueryParam.INCLUDE_SAMPLE.key(), sampleIds);
                }
            } else {
                query.append(VariantQueryParam.SAMPLE.key(), sampleIds);
            }
        }


        if (isValidParam(query, FAMILY)) {
            String familyId = query.getString(FAMILY.key());
            if (StringUtils.isEmpty(defaultStudyStr)) {
                throw VariantQueryException.missingStudyFor("family", familyId, null);
            }
            Family family = catalogManager.getFamilyManager().get(defaultStudyStr, familyId, null, sessionId).first();

            if (family.getMembers().isEmpty()) {
                throw VariantQueryException.malformedParam(FAMILY, familyId, "Empty family");
            }
            List<String> familyMembers = query.getAsStringList(FAMILY_MEMBERS.key());
            if (familyMembers.size() == 1) {
                throw VariantQueryException.malformedParam(FAMILY_MEMBERS, familyMembers.toString(), "Only one member provided");
            }

            // Use search instead of get to avoid smartResolutor to fetch all samples
            Set<Long> indexedSampleUids = catalogManager.getCohortManager()
                    .search(defaultStudyStr, new Query(CohortDBAdaptor.QueryParams.ID.key(), StudyEntry.DEFAULT_COHORT),
                            new QueryOptions(INCLUDE, CohortDBAdaptor.QueryParams.SAMPLE_UIDS.key()), sessionId)
                    .first()
                    .getSamples()
                    .stream()
                    .map(Sample::getUid).collect(Collectors.toSet());

            boolean multipleSamplesPerIndividual = false;
            List<Long> sampleUids = new ArrayList<>();
            Map<String, Long> individualToSampleUid = new HashMap<>();
            if (!familyMembers.isEmpty()) {
                family.getMembers().removeIf(member -> !familyMembers.contains(member.getId()));
                if (family.getMembers().size() != familyMembers.size()) {
                    List<String> actualFamilyMembers = family.getMembers().stream().map(Individual::getId).collect(Collectors.toList());
                    List<String> membersNotInFamily = familyMembers.stream()
                            .filter(member -> !actualFamilyMembers.contains(member))
                            .collect(Collectors.toList());
                    throw VariantQueryException.malformedParam(FAMILY_MEMBERS, familyMembers.toString(),
                            "Members " + membersNotInFamily + " not present in family '" + family.getId() + "'. "
                                    + "Family members: " + actualFamilyMembers);
                }
            }
            for (Iterator<Individual> iterator = family.getMembers().iterator(); iterator.hasNext();) {
                Individual member = iterator.next();
                int numSamples = 0;
                for (Iterator<Sample> sampleIt = member.getSamples().iterator(); sampleIt.hasNext();) {
                    Sample sample = sampleIt.next();
                    long uid = sample.getUid();
                    if (indexedSampleUids.contains(uid)) {
                        numSamples++;
                        sampleUids.add(uid);
                        individualToSampleUid.put(member.getId(), uid);
                    } else {
                        sampleIt.remove();
                    }
                }
                if (numSamples == 0) {
                    iterator.remove();
                }
                multipleSamplesPerIndividual |= numSamples > 1;
            }
            if (sampleUids.isEmpty()) {
                throw VariantQueryException.malformedParam(FAMILY, familyId, "Family not indexed in storage");
            }

            List<Sample> samples = catalogManager.getSampleManager().search(defaultStudyStr,
                    new Query(SampleDBAdaptor.QueryParams.UID.key(), sampleUids), new QueryOptions(INCLUDE, Arrays.asList(
                    SampleDBAdaptor.QueryParams.ID.key(),
                    SampleDBAdaptor.QueryParams.UID.key())), sessionId).getResults();
            Map<Long, Sample> sampleMap = samples.stream().collect(Collectors.toMap(Sample::getUid, s -> s));

            // By default, include all samples from the family
            if (!isValidParam(query, INCLUDE_SAMPLE)) {
                query.append(INCLUDE_SAMPLE.key(), samples.stream().map(Sample::getId).collect(Collectors.toList()));
            }

            // If filter FAMILY is among with MODE_OF_INHERITANCE, fill the list of genotypes.
            // Otherwise, add the samples from the family to the SAMPLES query param.
            if (isValidParam(query, FAMILY_SEGREGATION)) {
                if (isValidParam(query, GENOTYPE)) {
                    throw VariantQueryException.malformedParam(FAMILY_SEGREGATION, query.getString(FAMILY_SEGREGATION.key()),
                            "Can not be used along with filter \"" + GENOTYPE.key() + '"');
                }
                if (isValidParam(query, SAMPLE)) {
                    throw VariantQueryException.malformedParam(FAMILY_SEGREGATION, query.getString(FAMILY_SEGREGATION.key()),
                            "Can not be used along with filter \"" + SAMPLE.key() + '"');
                }
                if (multipleSamplesPerIndividual) {
                    throw VariantQueryException.malformedParam(FAMILY, familyId,
                            "Some individuals from this family have multiple indexed samples");
                }
                if (sampleUids.size() == 1) {
                    throw VariantQueryException.malformedParam(FAMILY_SEGREGATION, familyId,
                            "Only one member of the family is indexed in storage");
                }
                Pedigree pedigree = FamilyManager.getPedigreeFromFamily(family, null);
                PedigreeManager pedigreeManager = new PedigreeManager(pedigree);

                String proband = query.getString(FAMILY_PROBAND.key());
                String moiString = query.getString(FAMILY_SEGREGATION.key());
                if (moiString.equalsIgnoreCase("mendelianError") || moiString.equalsIgnoreCase("deNovo")) {
                    List<Member> children;
                    if (StringUtils.isNotEmpty(proband)) {
                        Member probandMember = pedigree.getMembers()
                                .stream()
                                .filter(member -> member.getId().equals(proband))
                                .findFirst()
                                .orElse(null);
                        if (probandMember == null) {
                            throw VariantQueryException.malformedParam(FAMILY_PROBAND, proband,
                                    "Individual '" + proband + "' " + "not found in family '" + familyId + "'.");
                        }
                        children = Collections.singletonList(probandMember);
                    } else {
                        children = pedigreeManager.getWithoutChildren();
                    }
                    List<String> childrenIds = children.stream().map(Member::getId).collect(Collectors.toList());
                    List<String> childrenSampleIds = new ArrayList<>(childrenIds.size());

                    for (String childrenId : childrenIds) {
                        Long sampleUid = individualToSampleUid.get(childrenId);
                        Sample sample = sampleMap.get(sampleUid);
                        if (sample == null) {
                            throw new VariantQueryException("Sample not found for individual \"" + childrenId + '"');
                        }
                        childrenSampleIds.add(sample.getId());
                    }

                    if (moiString.equalsIgnoreCase("deNovo")) {
                        query.put(SAMPLE_DE_NOVO.key(), childrenSampleIds);
                    } else {
                        query.put(SAMPLE_MENDELIAN_ERROR.key(), childrenSampleIds);
                    }
                } else if (moiString.equalsIgnoreCase("CompoundHeterozygous")) {
                    List<Member> children;
                    if (StringUtils.isNotEmpty(proband)) {
                        Member probandMember = pedigree.getMembers()
                                .stream()
                                .filter(member -> member.getId().equals(proband))
                                .findFirst()
                                .orElse(null);
                        if (probandMember == null) {
                            throw VariantQueryException.malformedParam(FAMILY_PROBAND, proband,
                                    "Individual '" + proband + "' " + "not found in family '" + familyId + "'.");
                        }
                        children = Collections.singletonList(probandMember);
                    } else {
                        children = pedigreeManager.getWithoutChildren();
                        if (children.size() > 1) {
                            String childrenStr = children.stream().map(Member::getId).collect(Collectors.joining("', '", "[ '", "' ]"));
                            throw new VariantQueryException(
                                    "Unsupported compoundHeterozygous method with families with more than one child."
                                    + " Specify proband with parameter '" + FAMILY_PROBAND.key() + "'."
                                    + " Available children: " + childrenStr);
                        }
                    }

                    Member child = children.get(0);

                    String childId = sampleMap.get(individualToSampleUid.get(child.getId())).getId();
                    String fatherId = MISSING_SAMPLE;
                    String motherId = MISSING_SAMPLE;
                    if (child.getFather() != null && child.getFather().getId() != null) {
                        Sample fatherSample = sampleMap.get(individualToSampleUid.get(child.getFather().getId()));
                        if (fatherSample != null) {
                            fatherId = fatherSample.getId();
                        }
                    }

                    if (child.getMother() != null && child.getMother().getId() != null) {
                        Sample motherSample = sampleMap.get(individualToSampleUid.get(child.getMother().getId()));
                        if (motherSample != null) {
                            motherId = motherSample.getId();
                        }
                    }

                    if (fatherId.equals(MISSING_SAMPLE) && motherId.equals(MISSING_SAMPLE)) {
                        throw VariantQueryException.malformedParam(FAMILY_SEGREGATION, moiString,
                                "Require at least one parent to get compound heterozygous");
                    }

                    query.append(SAMPLE_COMPOUND_HETEROZYGOUS.key(), Arrays.asList(childId, fatherId, motherId));
                } else {
                    if (family.getDisorders().isEmpty()) {
                        throw VariantQueryException.malformedParam(FAMILY, familyId, "Family doesn't have disorders");
                    }
                    Disorder disorder;
                    if (isValidParam(query, FAMILY_DISORDER)) {
                        String phenotypeId = query.getString(FAMILY_DISORDER.key());
                        disorder = family.getDisorders()
                                .stream()
                                .filter(familyDisorder -> familyDisorder.getId().equals(phenotypeId))
                                .findFirst()
                                .orElse(null);
                        if (disorder == null) {
                            throw VariantQueryException.malformedParam(FAMILY_DISORDER, phenotypeId,
                                    "Available disorders: " + family.getDisorders()
                                            .stream()
                                            .map(Disorder::getId)
                                            .collect(Collectors.toList()));
                        }

                    } else {
                        if (family.getDisorders().size() > 1) {
                            throw VariantQueryException.missingParam(FAMILY_DISORDER,
                                    "More than one disorder found for the family \"" + familyId + "\". "
                                            + "Available disorders: " + family.getDisorders()
                                            .stream()
                                            .map(Disorder::getId)
                                            .collect(Collectors.toList()));
                        }
                        disorder = family.getDisorders().get(0);
                    }

                    Map<String, List<String>> genotypes;
                    switch (moiString) {
                        case "MONOALLELIC":
                        case "monoallelic":
                        case "dominant":
                            genotypes = ModeOfInheritance.dominant(pedigree, disorder, ClinicalProperty.Penetrance.COMPLETE);
                            break;
                        case "MONOALLELIC_INCOMPLETE_PENETRANCE":
                        case "monoallelicIncompletePenetrance":
                            genotypes = ModeOfInheritance.dominant(pedigree, disorder, ClinicalProperty.Penetrance.INCOMPLETE);
                            break;
                        case "BIALLELIC":
                        case "biallelic":
                        case "recesive":
                            genotypes = ModeOfInheritance.recessive(pedigree, disorder, ClinicalProperty.Penetrance.COMPLETE);
                            break;
                        case "BIALLELIC_INCOMPLETE_PENETRANCE":
                        case "biallelicIncompletePenetrance":
                            genotypes = ModeOfInheritance.recessive(pedigree, disorder, ClinicalProperty.Penetrance.INCOMPLETE);
                            break;
                        case "XLINKED_MONOALLELIC":
                        case "XlinkedMonoallelic":
                            genotypes = ModeOfInheritance.xLinked(pedigree, disorder, true, ClinicalProperty.Penetrance.COMPLETE);
                            break;
                        case "XLINKED_BIALLELIC":
                        case "XlinkedBiallelic":
                            genotypes = ModeOfInheritance.xLinked(pedigree, disorder, false, ClinicalProperty.Penetrance.COMPLETE);
                            break;
                        case "YLINKED":
                        case "Ylinked":
                            genotypes = ModeOfInheritance.yLinked(pedigree, disorder, ClinicalProperty.Penetrance.COMPLETE);
                            break;
                        default:
                            throw VariantQueryException.malformedParam(FAMILY_SEGREGATION, moiString);
                    }
                    if (ModeOfInheritance.isEmptyMapOfGenotypes(genotypes)) {
                        throw VariantQueryException.malformedParam(FAMILY_SEGREGATION, moiString,
                                "Invalid segregation mode for the family '" + family.getId() + "'");
                    }

                    StringBuilder sb = new StringBuilder();

                    Map<Long, String> samplesUidToId = new HashMap<>();
                    for (Sample sample : samples) {
                        samplesUidToId.put(sample.getUid(), sample.getId());
                    }

                    Map<String, String> individualToSample = new HashMap<>();
                    for (Map.Entry<String, Long> entry : individualToSampleUid.entrySet()) {
                        individualToSample.put(entry.getKey(), samplesUidToId.get(entry.getValue()));
                    }

                    boolean firstSample = true;
                    for (Map.Entry<String, List<String>> entry : genotypes.entrySet()) {
                        if (firstSample) {
                            firstSample = false;
                        } else {
                            sb.append(AND);
                        }
                        sb.append(individualToSample.get(entry.getKey())).append(IS);

                        boolean firstGenotype = true;
                        for (String gt : entry.getValue()) {
                            if (firstGenotype) {
                                firstGenotype = false;
                            } else {
                                sb.append(OR);
                            }
                            sb.append(gt);
                        }
                    }

                    query.put(GENOTYPE.key(), sb.toString());
                }
            } else {
                if (isValidParam(query, FAMILY_DISORDER)) {
                    throw VariantQueryException.malformedParam(FAMILY_DISORDER, query.getString(FAMILY_DISORDER.key()),
                            "Require parameter \"" + FAMILY.key() + "\" and \"" + FAMILY_SEGREGATION.key() + "\" to use \""
                                    + FAMILY_DISORDER.key() + "\".");
                }

                List<String> sampleIds = new ArrayList<>();
                if (isValidParam(query, VariantQueryParam.SAMPLE)) {
//                    Pair<QueryOperation, List<String>> pair = splitValue(query.getString(VariantQueryParam.SAMPLE.key()));
//                    if (pair.getKey().equals(QueryOperation.AND)) {
//                        throw VariantQueryException.malformedParam(VariantQueryParam.SAMPLE, familyId,
//                                "Can not be used along with filter \"" + FAMILY.key() + "\" with operator AND (" + AND + ").");
//                    }
//                    sampleIds.addAll(pair.getValue());
                    throw VariantQueryException.malformedParam(VariantQueryParam.SAMPLE, familyId,
                            "Can not be used along with filter \"" + FAMILY.key() + "\"");
                }

                for (Sample sample : samples) {
                    sampleIds.add(sample.getId());
                }

                query.put(VariantQueryParam.SAMPLE.key(), String.join(OR, sampleIds));
            }
        } else if (isValidParam(query, FAMILY_MEMBERS)) {
            throw VariantQueryException.malformedParam(FAMILY_MEMBERS, query.getString(FAMILY_MEMBERS.key()),
                    "Require parameter \"" + FAMILY.key() + "\" to use \"" + FAMILY_MEMBERS.toString() + "\".");
        } else if (isValidParam(query, FAMILY_PROBAND)) {
            throw VariantQueryException.malformedParam(FAMILY_PROBAND, query.getString(FAMILY_PROBAND.key()),
                    "Require parameter \"" + FAMILY.key() + "\" to use \"" + FAMILY_PROBAND.toString() + "\".");
        } else if (isValidParam(query, FAMILY_SEGREGATION)) {
            throw VariantQueryException.malformedParam(FAMILY_SEGREGATION, query.getString(FAMILY_SEGREGATION.key()),
                    "Require parameter \"" + FAMILY.key() + "\" to use \"" + FAMILY_SEGREGATION.toString() + "\".");
        } else if (isValidParam(query, FAMILY_DISORDER)) {
            throw VariantQueryException.malformedParam(FAMILY_DISORDER, query.getString(FAMILY_DISORDER.key()),
                    "Require parameter \"" + FAMILY.key() + "\" and \"" + FAMILY_SEGREGATION.key() + "\" to use \""
                            + FAMILY_DISORDER.toString() + "\".");
        }

        if (isValidParam(query, PANEL)) {
            String assembly = null;
            Set<String> geneNames = new HashSet<>();
            Set<Region> regions = new HashSet<>();
            Set<String> variants = new HashSet<>();
            List<String> panels = query.getAsStringList(PANEL.key());
            for (String panelId : panels) {
                Panel panel = getPanel(defaultStudyStr, panelId, sessionId);
                for (GenePanel genePanel : panel.getGenes()) {
                    geneNames.add(genePanel.getName());
                }

                if (CollectionUtils.isNotEmpty(panel.getRegions()) || CollectionUtils.isNotEmpty(panel.getVariants())) {
                    if (assembly == null) {
                        Project project = getProjectFromQuery(query, sessionId,
                                new QueryOptions(INCLUDE, ProjectDBAdaptor.QueryParams.ORGANISM.key()));
                        assembly = project.getOrganism().getAssembly();
                    }
                    if (panel.getRegions() != null) {
                        for (DiseasePanel.RegionPanel region : panel.getRegions()) {
                            for (DiseasePanel.Coordinate coordinate : region.getCoordinates()) {
                                if (coordinate.getAssembly().equalsIgnoreCase(assembly)) {
                                    regions.add(Region.parseRegion(coordinate.getLocation()));
                                }
                            }
                        }
                    }
                    // TODO: Check assembly of variants
//                    if (panel.getVariants() != null) {
//                        for (DiseasePanel.VariantPanel variant : panel.getVariants()) {
//                            variant.getId()
//                        }
//                    }
                }
            }

            if (isValidParam(query, GENE)) {
                geneNames.addAll(query.getAsStringList(GENE.key()));
            }
            query.put(GENE.key(), geneNames);
            query.put(SKIP_MISSING_GENES, true);
        }

        logger.debug("Catalog parsed query : " + VariantQueryUtils.printQuery(query));

        return query;
    }

    /**
     * Get the panel from catalog. If the panel is not found in the study, or the study is null, search through the global panels.
     *
     * @param studyId   StudyId
     * @param panelId   PanelId
     * @param sessionId users sessionId
     * @return The panel
     * @throws CatalogException if the panel does not exist, or the user does not have permissions to see it.
     */
    protected Panel getPanel(String studyId, String panelId, String sessionId) throws CatalogException {
        Panel panel = null;
        if (StringUtils.isNotEmpty(studyId)) {
            try {
                panel = catalogManager.getPanelManager().get(studyId, panelId, null, sessionId).first();
            } catch (CatalogException e) {
                logger.debug("Ignore Panel not found", e);
            }
        }
        if (panel == null) {
            panel = catalogManager.getPanelManager().get(PanelManager.INSTALLATION_PANELS, panelId, null, sessionId).first();
        }
        return panel;
    }

    public String getDefaultStudyId(Collection<String> studies) throws CatalogException {
        final String defaultStudyId;
        if (studies.size() == 1) {
            defaultStudyId = studies.iterator().next();
        } else {
            defaultStudyId = null;
        }
        return defaultStudyId;
    }

    public Integer getReleaseFilter(Query query, String sessionId) throws CatalogException {
        Integer release;
        if (isValidParam(query, VariantQueryParam.RELEASE)) {
            release = query.getInt(VariantQueryParam.RELEASE.key(), -1);
            if (release <= 0) {
                throw VariantQueryException.malformedParam(VariantQueryParam.RELEASE, query.getString(VariantQueryParam.RELEASE.key()));
            }
            Project project = getProjectFromQuery(query, sessionId,
                    new QueryOptions(INCLUDE, ProjectDBAdaptor.QueryParams.CURRENT_RELEASE.key()));
            int currentRelease = project.getCurrentRelease();
            if (release > currentRelease) {
                throw VariantQueryException.malformedParam(VariantQueryParam.RELEASE, query.getString(VariantQueryParam.RELEASE.key()));
            } else if (release == currentRelease) {
                // Using latest release. We don't need to filter by release!
                release = null;
            } // else, filter by release

        } else {
            release = null;
        }
        return release;
    }

    public List<List<String>> getTriosFromFamily(
            String studyFqn, Family family, VariantStorageMetadataManager metadataManager, boolean skipIncompleteFamily, String sessionId)
            throws StorageEngineException, CatalogException {
        int studyId = metadataManager.getStudyId(studyFqn);
        List<List<String>> trios = new LinkedList<>();
        Map<Long, Individual> members = family.getMembers().stream().collect(Collectors.toMap(Individual::getUid, i -> i));
        for (Individual individual : family.getMembers()) {
            String fatherSample = null;
            String motherSample = null;
            String childSample = null;

            if (CollectionUtils.isNotEmpty(individual.getSamples())) {
                for (Sample sample : individual.getSamples()) {
                    sample = catalogManager.getSampleManager().search(studyFqn,
                            new Query(SampleDBAdaptor.QueryParams.UID.key(), sample.getUid()),
                            new QueryOptions(QueryOptions.INCLUDE, SampleDBAdaptor.QueryParams.ID.key()), sessionId).first();
                    Integer sampleId = metadataManager.getSampleId(studyId, sample.getId(), true);
                    if (sampleId != null) {
                        childSample = sample.getId();
                        break;
                    }
                }
            }
            if (individual.getFather() != null && members.containsKey(individual.getFather().getUid())) {
                Individual father = members.get(individual.getFather().getUid());
                if (CollectionUtils.isNotEmpty(father.getSamples())) {
                    for (Sample sample : father.getSamples()) {
                        sample = catalogManager.getSampleManager().search(studyFqn,
                                new Query(SampleDBAdaptor.QueryParams.UID.key(), sample.getUid()),
                                new QueryOptions(QueryOptions.INCLUDE, SampleDBAdaptor.QueryParams.ID.key()), sessionId).first();
                        Integer sampleId = metadataManager.getSampleId(studyId, sample.getId(), true);
                        if (sampleId != null) {
                            fatherSample = sample.getId();
                            break;
                        }
                    }
                }
            }
            if (individual.getMother() != null && members.containsKey(individual.getMother().getUid())) {
                Individual mother = members.get(individual.getMother().getUid());
                if (CollectionUtils.isNotEmpty(mother.getSamples())) {
                    for (Sample sample : mother.getSamples()) {
                        sample = catalogManager.getSampleManager().search(studyFqn,
                                new Query(SampleDBAdaptor.QueryParams.UID.key(), sample.getUid()),
                                new QueryOptions(QueryOptions.INCLUDE, SampleDBAdaptor.QueryParams.ID.key()), sessionId).first();
                        Integer sampleId = metadataManager.getSampleId(studyId, sample.getId(), true);
                        if (sampleId != null) {
                            motherSample = sample.getId();
                            break;
                        }
                    }
                }
            }

            // Allow one missing parent
            if (childSample != null && (fatherSample != null || motherSample != null)) {
                trios.add(Arrays.asList(
                        fatherSample == null ? "-" : fatherSample,
                        motherSample == null ? "-" : motherSample,
                        childSample));
            }
        }
        if (trios.size() == 0) {
            if (skipIncompleteFamily) {
                logger.debug("Skip family '" + family.getId() + "'. ");
            } else {
                throw new StorageEngineException("Can not calculate mendelian errors on family '" + family.getId() + "'");
            }
        }
        return trios;
    }

    public abstract class FilterValidator {
        protected final QueryOptions RELEASE_OPTIONS = new QueryOptions(INCLUDE, Arrays.asList(
                FileDBAdaptor.QueryParams.ID.key(),
                FileDBAdaptor.QueryParams.NAME.key(),
                FileDBAdaptor.QueryParams.INDEX.key(),
                FileDBAdaptor.QueryParams.RELEASE.key()));

        /**
         * Splits the value from the query (if any) and translates the IDs to numerical Ids.
         * If a release value is given, checks that every element is part of that release.
         * @param query        Query with the data
         * @param param        Param to modify
         * @param release      Release filter, if any
         * @param sessionId    SessionId
         * @param defaultStudy Default study
         * @throws CatalogException if there is any catalog error
         */
        protected void processFilter(Query query, VariantQueryParam param, Integer release, String sessionId, String defaultStudy)
                throws CatalogException {
            if (VariantQueryUtils.isValidParam(query, param)) {
                String valuesStr = query.getString(param.key());
                // Do not try to transform ALL or NONE values
                if (isNoneOrAll(valuesStr)) {
                    return;
                }
                QueryOperation queryOperation = getQueryOperation(valuesStr);
                List<String> rawValues = splitValue(valuesStr, queryOperation);
                List<String> values = getValuesToValidate(rawValues);
                List<String> validatedValues = validate(defaultStudy, values, release, param, sessionId);

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rawValues.size(); i++) {
                    String rawValue = rawValues.get(i);
                    String value = values.get(i);
                    String validatedValue = validatedValues.get(i);
                    if (sb.length() > 0) {
                        sb.append(queryOperation.separator());
                    }

                    if (!value.equals(validatedValue)) {
                        sb.append(StringUtils.replace(rawValue, value, validatedValue, 1));
                    } else {
                        sb.append(rawValue);
                    }

                }
                query.put(param.key(), sb.toString());
            }
        }

        protected QueryOperation getQueryOperation(String valuesStr) {
            QueryOperation queryOperation = VariantQueryUtils.checkOperator(valuesStr);
            if (queryOperation == null) {
                queryOperation = QueryOperation.OR;
            }
            return queryOperation;
        }

        protected List<String> splitValue(String valuesStr, QueryOperation queryOperation) {
            return VariantQueryUtils.splitValue(valuesStr, queryOperation);
        }

        protected List<String> getValuesToValidate(List<String> rawValues) {
            return rawValues.stream()
                    .map(value -> {
                        value = isNegated(value) ? removeNegation(value) : value;
                        String[] strings = VariantQueryUtils.splitOperator(value);
                        boolean withComparisionOperator = strings[0] != null;
                        if (withComparisionOperator) {
                            value = strings[0];
                        }
                        return value;
                    })
                    .collect(Collectors.toList());
        }


        protected abstract List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                                 String sessionId)
                throws CatalogException;

        protected final void checkRelease(Integer release, int resourceRelease, VariantQueryParam param, String value) {
            if (release != null && resourceRelease > release) {
                throw wrongReleaseException(param, value, release);
            }
        }

        protected final <T extends PrivateStudyUid> List<String> validate(String defaultStudyStr, List<String> values, Integer release,
                                                                          VariantQueryParam param, ResourceManager<T> manager,
                                                                          Function<T, String> getId, Function<T, Integer> getRelease,
                                                                          Consumer<T> valueValidator, String sessionId)
                throws CatalogException {
            DataResult<T> queryResult = manager.get(defaultStudyStr, values, RELEASE_OPTIONS, sessionId);
            List<String> validatedValues = new ArrayList<>(values.size());
            for (T value : queryResult.getResults()) {
                if (valueValidator != null) {
                    valueValidator.accept(value);
                }
                String id = getId.apply(value);
                validatedValues.add(id);
                checkRelease(release, getRelease.apply(value), param, id);
            }
            return validatedValues;
        }
    }


    public class StudyFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId) throws CatalogException {
            if (release == null) {
                String userId = catalogManager.getUserManager().getUserId(sessionId);
                List<Study> studies = catalogManager.getStudyManager().resolveIds(values, userId);
                return studies.stream().map(Study::getFqn).collect(Collectors.toList());
            } else {
                List<String> validatedValues = new ArrayList<>(values.size());
                DataResult<Study> queryResult = catalogManager.getStudyManager().get(values, RELEASE_OPTIONS, false, sessionId);
                for (Study study : queryResult.getResults()) {
                    validatedValues.add(study.getFqn());
                    checkRelease(release, study.getRelease(), param, study.getFqn());
                }
                return validatedValues;
            }
        }
    }

    public class FileFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId)
                throws CatalogException {
            if (release == null) {
                DataResult<File> files = catalogManager.getFileManager().get(defaultStudyStr, values,
                        FileManager.INCLUDE_FILE_IDS, sessionId);
                return files.getResults().stream().map(File::getName).collect(Collectors.toList());
            } else {
                return validate(defaultStudyStr, values, release, param, catalogManager.getFileManager(), File::getName,
                        file -> ((int) file.getIndex().getRelease()), file -> {
                            if (file.getIndex() == null
                                    || file.getIndex().getStatus() == null
                                    || file.getIndex().getStatus().getName() == null
                                    || !file.getIndex().getStatus().getName().equals(Status.READY)) {
                                throw new VariantQueryException("File '" + file.getName() + "' is not indexed");
                            }
                        },
                        sessionId);

            }
        }
    }

    public class SampleFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId) throws CatalogException {
            if (release == null) {
                DataResult<Sample> samples = catalogManager.getSampleManager().get(defaultStudyStr, values,
                        SampleManager.INCLUDE_SAMPLE_IDS, sessionId);
                return samples.getResults().stream().map(Sample::getId).collect(Collectors.toList());
            } else {
                return validate(defaultStudyStr, values, release, param, catalogManager.getSampleManager(),
                        Sample::getId, Sample::getRelease, null, sessionId);
            }
        }
    }

    public class GenotypeFilterValidator extends SampleFilterValidator {

        @Override
        protected QueryOperation getQueryOperation(String valuesStr) {
            Map<Object, List<String>> genotypesMap = new HashMap<>();
            return VariantQueryUtils.parseGenotypeFilter(valuesStr, genotypesMap);
        }

        @Override
        protected List<String> splitValue(String valuesStr, QueryOperation queryOperation) {
            Map<Object, List<String>> genotypesMap = new LinkedHashMap<>();
            VariantQueryUtils.parseGenotypeFilter(valuesStr, genotypesMap);

            return genotypesMap.entrySet().stream().map(entry -> entry.getKey() + ":" + String.join(",", entry.getValue()))
                    .collect(Collectors.toList());
        }

        @Override
        protected List<String> getValuesToValidate(List<String> rawValues) {
            return rawValues.stream().map(value -> value.split(":")[0]).collect(Collectors.toList());
        }
    }

    public class CohortFilterValidator extends FilterValidator {

        @Override
        protected List<String> validate(String defaultStudyStr, List<String> values, Integer release, VariantQueryParam param,
                                        String sessionId)
                throws CatalogException {
            if (release == null) {
                // Query cohort by cohort if
                if (StringUtils.isEmpty(defaultStudyStr) || values.stream().anyMatch(value -> value.contains(":"))) {
                    List<String> validated = new ArrayList<>(values.size());
                    for (String value : values) {
                        String[] split = VariantQueryUtils.splitStudyResource(value);
                        String study = defaultStudyStr;
                        if (split.length == 2) {
                            study = split[0];
                            value = split[1];
                        }
                        Cohort cohort = catalogManager.getCohortManager().get(study, value, CohortManager.INCLUDE_COHORT_IDS, sessionId)
                                .first();
                        String fqn = catalogManager.getStudyManager().get(study,
                                new QueryOptions(INCLUDE, StudyDBAdaptor.QueryParams.FQN.key()), sessionId).first().getFqn();
                        if (fqn.equals(defaultStudyStr)) {
                            validated.add(cohort.getId());
                        } else {
                            validated.add(fqn + ":" + cohort.getId());
                        }
                    }
                    return validated;
                } else {
                    DataResult<Cohort> cohorts = catalogManager.getCohortManager().get(defaultStudyStr, values,
                            CohortManager.INCLUDE_COHORT_IDS, sessionId);
                    return cohorts.getResults().stream().map(Cohort::getId).collect(Collectors.toList());
                }
            } else {
                return validate(defaultStudyStr, values, release, param, catalogManager.getCohortManager(),
                        Cohort::getId, Cohort::getRelease, null, sessionId);
            }
        }
    }

}
