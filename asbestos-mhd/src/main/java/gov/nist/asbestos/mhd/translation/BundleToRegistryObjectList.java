package gov.nist.asbestos.mhd.translation;


import gov.nist.asbestos.asbestorCodesJaxb.Code;
import gov.nist.asbestos.client.Base.IVal;
import gov.nist.asbestos.client.resolver.IdBuilder;
import gov.nist.asbestos.client.resolver.Ref;
import gov.nist.asbestos.client.resolver.ResolverConfig;
import gov.nist.asbestos.client.resolver.ResourceMgr;
import gov.nist.asbestos.mhd.transactionSupport.AssigningAuthorities;
import gov.nist.asbestos.mhd.transactionSupport.CodeTranslator;
import gov.nist.asbestos.client.resolver.ResourceWrapper;
import gov.nist.asbestos.mhd.translation.attribute.EntryUuid;
import gov.nist.asbestos.simapi.validation.Val;
import gov.nist.asbestos.simapi.validation.ValE;
import oasis.names.tc.ebxml_regrep.xsd.rim._3.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;

import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 */

// TODO enable runtime assertions with ClassLoader.getSystemClassLoader().setClassAssertionStatus("gov.nist");
// TODO - add legalAuthenticator
// TODO - add sourcePatientInfo
// TODO - add referenceIdList
// TODO - add author
// TODO - add case where Patient not in bundle?????

/**
 * Association id = ID06
 *    source = SubmissionSet_ID02
 *    target = Document_ID01
 *
 * RegistryPackage id = 234...
 *
 * ExtrinsicObject id = ID07
 */


public class BundleToRegistryObjectList implements IVal {
//    static private final Logger logger = Logger.getLogger(BundleToRegistryObjectList.class)
    private static List<Class<?>> acceptableResourceTypes = Arrays.asList(DocumentManifest.class, DocumentReference.class, Binary.class, ListResource.class);
    private static String comprehensiveMetadataProfile = "http://ihe.net/fhir/StructureDefinition/IHE_MHD_Provide_Comprehensive_DocumentBundle";
    private static String minimalMetadataProfile = "http://ihe.net/fhir/StructureDefinition/IHE_MHD_Provide_Minimal_DocumentBundle";
    private static List<String> profiles = Arrays.asList(comprehensiveMetadataProfile, minimalMetadataProfile);
    private static String baseContentId = ".de1e4efca5ccc4886c8528535d2afb251e0d5fa31d58a815@ihexds.nist.gov";
    private static String mhdProfileRef = "MHD Profile - Rev 3.1";
    private static Map<String, String> buildTypeMap() {
        return Collections.unmodifiableMap(Stream.of(
                new AbstractMap.SimpleEntry<>("replaces", "urn:ihe:iti:2007:AssociationType:RPLC"),
                new AbstractMap.SimpleEntry<>("transforms", "urn:ihe:iti:2007:AssociationType:XFRM"),
                new AbstractMap.SimpleEntry<>("signs", "urn:ihe:iti:2007:AssociationType:signs"),
                new AbstractMap.SimpleEntry<>("appends", "urn:ihe:iti:2007:AssociationType:APND"))
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue)));
    }
    private static final String DRTable = "MHD: Table 4.5.1.1-1";

    private CodeTranslator codeTranslator;
    private Configuration config;
    private AssigningAuthorities assigningAuthorities;
    private ResourceMgr rMgr;
    private Val val;
    private IdBuilder idBuilder;
    private Map<String, byte[]> documentContents = new HashMap<>();

    public RegistryObjectListType build(Bundle bundle) {
        Objects.requireNonNull(val);
        Objects.requireNonNull(bundle);
        Objects.requireNonNull(rMgr);
        Objects.requireNonNull(idBuilder);

        scanBundleForAcceptability(bundle, rMgr);

        return buildRegistryObjectList();
    }

    // TODO handle List/Folder or signal error
    public RegistryObjectListType buildRegistryObjectList() {
        Objects.requireNonNull(val);
        Objects.requireNonNull(rMgr);

        //rMgr.setBundle(bundle);
        //scanBundleForAcceptability(bundle, rMgr);
        RegistryObjectListType rol = new RegistryObjectListType();

        List<ResourceWrapper> docMans = rMgr.getBundleResources().stream()
                .filter(rw -> rw.getResource().getClass().equals(DocumentManifest.class))
                .collect(Collectors.toList());

        List<ResourceWrapper> docRefs = rMgr.getBundleResources().stream()
                .filter(rw -> rw.getResource().getClass().equals(DocumentReference.class))
                .collect(Collectors.toList());

        if (docMans.size() != 1)
            val.add(new ValE("Found " + docMans.size() + " DocumentManifests - one required").asError());

        RegistryPackageType ss = null;
        if (docMans.size() > 0) {
            ResourceWrapper dm = docMans.get(0);
            ss = createSubmissionSet(dm);
            rol.getIdentifiable().add(new JAXBElement<>(new QName("urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0", "RegistryPackage"), RegistryPackageType.class, ss));
        }

        List<ExtrinsicObjectType> eos = docRefs.stream()
                .map(this::createExtrinsicObject)
                .collect(Collectors.toList());

        for (ExtrinsicObjectType eo : eos) {
            rol.getIdentifiable().add(new JAXBElement<>(new QName("urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0", "ExtrinsicObject"), ExtrinsicObjectType.class, eo));

            if (ss != null) {
                AssociationType1 a = createSSDEAssociation(ss, eo);
                rol.getIdentifiable().add((new JAXBElement<>(new QName("urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0", "Association"), AssociationType1.class, a)));
            }
        }
        return rol;
    }


//    private void buildSubmission() {
//        PnrWrapper submission = new PnrWrapper()
//        submission.contentId = 'm' + baseContentId
//
//        int index = 1
//
//        StringWriter writer = new StringWriter()
//        MarkupBuilder xml = new MarkupBuilder(writer)
//        xml.RegistryObjectList(xmlns: 'urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0') {
//            rMgr.resources.each { Ref url, ResourceWrapper resource ->
//                if (resource.resource instanceof DocumentManifest) {
//                    DocumentManifest dm = (DocumentManifest) resource.resource
//                    createSubmissionSet(xml, resource)
//                    addSubmissionSetAssociations(xml, resource)
//                }
//                else if (resource.resource instanceof DocumentReference) {
//                    DocumentReference dr = (DocumentReference) resource.resource
//                    ResourceWrapper loadedResource = rMgr.resolveReference(resource, new Ref(dr.content[0].attachment.url), new ResolverConfig().internalRequired())
//                    if (!(loadedResource.resource instanceof Binary))
//                        val.err(new Val()
//                        .msg("Binary ${dr.content[0].attachment.url} is not available in Bundle."))
//                    Binary b = (Binary) loadedResource.resource
//                    b.id = dr.masterIdentifier.value
//                    Attachment a = new Attachment()
//                    a.contentId = Integer.toString(index) + baseContentId
//                    a.contentType = b.contentType
//                    a.content = b.content
//                    submission.attachments << a
//                    index++
//
//                    createExtrinsicObject(xml, resource)
//                    documents[resource.assignedId] = a.contentId
//                    addRelationshipAssociations(xml, resource)
//                }
//            }
//        }
//        submission.registryObjectList = writer.toString()
//    }

    private AssociationType1 createSSDEAssociation(RegistryPackageType ss, ExtrinsicObjectType de) {
        return createAssociation("urn:oasis:names:tc:ebxml-regrep:AssociationType:HasMember", ss.getId(), de.getId(), "SubmissionSetStatus", Collections.singletonList("Original"));


//        DocumentManifest dm = (DocumentManifest) resource.resource
//        if (!dm.content) return
//        dm.content.each { Reference ref ->
//            ResourceWrapper loadedResource = rMgr.resolveReference(resource, new Ref(ref.reference), new ResolverConfig().internalRequired())
//
//            if (!loadedResource.resource)
//                val.err(new Val()
//                .msg("DocumentManifest references ${ref.resource} - ${loadedResource.ref} is not included in the bundle"))
//            createAssociation(xml, 'urn:oasis:names:tc:ebxml-regrep:AssociationType:HasMember', resource, loadedResource.url, 'SubmissionSetStatus', ['Original'])
//        }
    }

    private void addRelationshipAssociations(ResourceWrapper resource) {
//        assert resource.resource instanceof DocumentReference
//        DocumentReference dr = (DocumentReference) resource.resource
//        if (!dr.relatesTo || dr.relatesTo.size() == 0) return
//
//        // GET relatesTo reference, extract entryUUID, assemble Association
//        dr.relatesTo.each { DocumentReference.DocumentReferenceRelatesToComponent comp ->
//            String type = comp.getCode().toCode()
//            String xdsType = typeMap[type]
//            if (!xdsType)
//                val.err(new Val()
//                .msg("RelatesTo type (${type}) cannot be translated to XDS."))
//
//            Reference ref = comp.target
//
//            ResourceWrapper referencedDocRef = rMgr.resolveReference(resource, new Ref(ref.reference), new ResolverConfig().externalRequired())
//
//            if (!referencedDocRef.resource) {
//                val.err(new Val()
//                        .msg("Trying to load ${xdsType} Association - ${ref.reference} cannot be resolved"))
//                return
//            }
//            createAssociation(xml, xdsType, resource, referencedDocRef.url, null, null)
//        }
    }

    private AssociationType1 createAssociation(String type, ResourceWrapper source, Ref target, String slotName, List<String> slotValues) {
        AssociationType1 at = new AssociationType1();
        val.add(new ValE("Association(" + type + ") source=" + source + " target=" + target));
        at.setSourceObject(source.getAssignedId());
        at.setTargetObject(target.getId());
        at.setAssociationType(type);
        at.setId(rMgr.allocateSymbolicId());
        addSlot(at, slotName, slotValues);
        return at;
    }

    public AssociationType1 createAssociation(String type, String sourceId, String targetId, String slotName, List<String> slotValues) {
        AssociationType1 at = new AssociationType1();
        at.setSourceObject(sourceId);
        at.setTargetObject(targetId);
        at.setAssociationType(type);
        at.setId(rMgr.allocateSymbolicId());
        if (slotName != null)
            addSlot(at, slotName, slotValues);
        return at;
    }

    private RegistryPackageType createSubmissionSet(ResourceWrapper resource) {
        DocumentManifest dm = (DocumentManifest) resource.getResource();

        RegistryPackageType ss = new RegistryPackageType();

        ValE vale = new ValE(val);
        ValE tr;
        vale.setMsg("DocumentManifest to SubmissionSet");

        val.add(new ValE("SubmissionSet(" + resource.getAssignedId() + ")"));
        ss.setId(resource.getAssignedId());
        ss.setObjectType("urn:oasis:names:tc:ebxml-regrep:ObjectType:RegistryObject:RegistryPackage");

        if (dm.hasCreated())
            addSlot(ss, "submissionTime", translateDateTime(dm.getCreated()));
        if (dm.hasDescription())
            addName(ss, dm.getDescription());
        addClassification(ss, "urn:uuid:a54d6aa5-d40d-43f9-88c5-b4633d873bdd", rMgr.allocateSymbolicId(), resource.getAssignedId());
        if (dm.hasType())
            addClassificationFromCodeableConcept(ss, dm.getType(), CodeTranslator.CONTENTTYPECODE, resource.getAssignedId(), vale);
        if (!dm.hasMasterIdentifier())
            val.add(new ValE("DocumentManifest.masterIdentifier not present - declared by IHE to be [1..1]").asError());
        else
            addExternalIdentifier(ss, CodeTranslator.SS_UNIQUEID, unURN(dm.getMasterIdentifier().getValue()), rMgr.allocateSymbolicId(), resource.getAssignedId(), "XDSSubmissionSet.uniqueId", idBuilder);
        if (dm.hasSource())
            addExternalIdentifier(ss, CodeTranslator.SS_SOURCEID, unURN(dm.getMasterIdentifier().getValue()), rMgr.allocateSymbolicId(), resource.getAssignedId(), "XDSSubmissionSet.sourceId", null);
        if (dm.hasSubject() && dm.getSubject().hasReference())
            addSubject(ss, resource,  new Ref(dm.getSubject()), CodeTranslator.SS_PID, "XDSSubmissionSet.patientId", vale);
        return ss;
    }

    public ExtrinsicObjectType createExtrinsicObject(ResourceWrapper resource) {
        Objects.requireNonNull(val);
        Objects.requireNonNull(rMgr);

        ValE vale = new ValE(val);
        ValE tr;
        vale.setMsg("DocumentReference to DocumentEntry");

        ExtrinsicObjectType eo = new ExtrinsicObjectType();
        eo.setObjectType("urn:uuid:7edca82f-054d-47f2-a032-9b2a5b5186c1");

        DocumentReference dr = (DocumentReference) resource.getResource();
        vale.add(new ValE("Content section is [1..1]").addIheRequirement(DRTable));
        if (dr.getContent() == null || dr.getContent().isEmpty()) {
            vale.add(new ValE("DocumentReference has no content section").asError());
            return eo;
        }
        if (dr.getContent().size() > 1) {
            vale.add(new ValE("DocumentReference has multiple content sections").asError());
            return eo;
        }
        vale.add(new ValE("Content.Attachment section is [1..1]").addIheRequirement(DRTable));
        if (dr.getContent().get(0).getAttachment() == null) {
            vale.add(new ValE("DocumentReference has no content/attachment").asError());
            return eo;
        }

        DocumentReference.DocumentReferenceContextComponent context = dr.getContext();
        DocumentReference.DocumentReferenceContentComponent content = dr.getContent().get(0);
        Attachment attachment = content.getAttachment();

        new EntryUuid().setVal(vale).setrMgr(rMgr).setResource(resource).assignId(dr.getIdentifier());

        eo.setId(resource.getAssignedId());
        eo.setObjectType("urn:uuid:7edca82f-054d-47f2-a032-9b2a5b5186c1");

        tr = vale.add(new ValE("content.attachment.contentType is [1..1]").addIheRequirement(DRTable));
        tr.add(new ValE("content.attachment.contentType to mimeType").asTranslation());
//        if (content.getAttachment().getContentType() == null)
//            tr.add(new ValE("content.attachment.contentType not present").asError());
//        else
//            eo.setMimeType(content.getAttachment().getContentType());

        if (dr.getDate() != null) {
            vale.addTr(new ValE("creationTime"));
            addSlot(eo, "creationTime", translateDateTime(dr.getDate()));
        }
        if (dr.hasStatus()) {
            vale.addTr(new ValE("availabilityStatus"));
            Enumerations.DocumentReferenceStatus fStatus = dr.getStatus();
            String status = null;
            if (fStatus == Enumerations.DocumentReferenceStatus.CURRENT)
                status = "urn:oasis:names:tc:ebxml-regrep:StatusType:Approved";
            else if (fStatus == Enumerations.DocumentReferenceStatus.SUPERSEDED)
                status = "urn:oasis:names:tc:ebxml-regrep:StatusType:Deprecated";
            if (status != null)
                eo.setStatus(status);
        }

        if (context != null) {
            Period period = context.getPeriod();
            if (period != null) {
                if (period.hasStart()) {
                    vale.addTr(new ValE("serviceStartTime"));
                    addSlot(eo, "serviceStartTime", translateDateTime(period.getStart()));
                }
                if (period.hasEnd()) {
                    vale.addTr(new ValE("serviceStopTime"));
                    addSlot(eo, "serviceStopTime", translateDateTime(period.getEnd()));
                }
            }
            if (context.hasSourcePatientInfo()) {
                vale.addTr(new ValE("sourcePatientInfo"));
                addSourcePatientInfo(eo, resource, context.getSourcePatientInfo(), vale);
            }
            if (context.hasFacilityType()) {
                vale.addTr(new ValE("facilityType"));
                addClassificationFromCodeableConcept(eo, context.getFacilityType(), CodeTranslator.HCFTCODE, resource.getAssignedId(), vale);
            }
            if (context.hasPracticeSetting()) {
                vale.addTr(new ValE("practiceSetting"));
                addClassificationFromCodeableConcept(eo, context.getPracticeSetting(), CodeTranslator.PRACCODE, resource.getAssignedId(), vale);
            }
            if (context.hasEvent()) {
                vale.addTr(new ValE("eventCode"));
                addClassificationFromCodeableConcept(eo, context.getEventFirstRep(), CodeTranslator.EVENTCODE, resource.getAssignedId(), vale);
            }
        }
        if (attachment.hasLanguage()) {
            vale.addTr(new ValE("languageCode"));
            addSlot(eo, "languageCode", attachment.getLanguage());
        }
//        if (attachment.hasUrl()) {
//            vale.addTr(new ValE("repositoryUniqueId"));
//            addSlot(eo, "repositoryUniqueId", attachment.getRef());
//        }
        if (attachment.hasHash()) {
            vale.addTr(new ValE("hash"));
            Base64BinaryType hash64 = attachment.getHashElement();
            byte[] hash = hash64.getValue();
            String hashString = DatatypeConverter.printHexBinary(hash).toLowerCase();
            addSlot(eo, "hash", hashString);
        }
        if (dr.hasDescription()) {
            vale.addTr(new ValE("description"));
            addName(eo, dr.getDescription());
        }
        if (attachment.hasTitle()) {
            vale.addTr(new ValE("title"));
            addDescription(eo, attachment.getTitle());
        }
        if (attachment.hasCreation()) {
            vale.addTr(new ValE("creationTime"));
            addCreationTime(eo, attachment.getCreation());
        }
        if (dr.hasType()) {
            tr = vale.addTr(new ValE("typeCode"));
            addClassificationFromCodeableConcept(eo, dr.getType(), CodeTranslator.TYPECODE, resource.getAssignedId(), tr);
        }
        if (dr.hasCategory()) {
            tr = vale.addTr(new ValE("classCode"));
            addClassificationFromCodeableConcept(eo, dr.getCategoryFirstRep(), CodeTranslator.CLASSCODE, resource.getAssignedId(), tr);
        }
        if (dr.hasSecurityLabel()) {
            tr = vale.addTr(new ValE("confCode"));
            addClassificationFromCoding(eo, dr.getSecurityLabel().get(0).getCoding().get(0), CodeTranslator.CONFCODE, resource.getAssignedId(), tr);
        }
        if(content.hasFormat()) {
            tr = vale.addTr(new ValE("formatCode"));
            addClassificationFromCoding(eo, dr.getContent().get(0).getFormat(), CodeTranslator.FORMATCODE, resource.getAssignedId(), tr);
        }

        tr = vale.add(new ValE("DocumentReference.masterIdentifier is [1..1]").addIheRequirement(DRTable));
        if (!dr.hasMasterIdentifier())
            tr.add(new ValE("masterIdentifier not present").asError());
        else {
            tr.add(new ValE("masterIdentifier").asTranslation());
            addExternalIdentifier(eo, CodeTranslator.DE_UNIQUEID, unURN(dr.getMasterIdentifier().getValue()), rMgr.allocateSymbolicId(), resource.getAssignedId(), "XDSDocumentEntry.uniqueId", idBuilder);
        }

        tr = vale.add(new ValE("DocumentReference.subject is [1..1]").addIheRequirement(DRTable));
        if (!dr.hasSubject() || !dr.getSubject().hasReference()) {
            tr.add(new ValE("subject not present or has no reference").asError());
        } else {
            vale.add(new ValE("subject to Patient Id").asTranslation());
            addSubject(eo, resource,  new Ref(dr.getSubject()), CodeTranslator.DE_PID, "XDSDocumentEntry.patientId", tr);
        }
        if (dr.hasAuthor()) {
            tr = vale.addTr(new ValE("author"));
            ResourceWrapper containing = new ResourceWrapper();
            containing.setResource(dr);
            for (Reference reference : dr.getAuthor()) {
                Optional<ResourceWrapper> contained = rMgr.resolveReference(containing, new Ref(reference.getReference()), new ResolverConfig().containedRequired(), tr);
                if (contained.isPresent()) {
                    IBaseResource resource1 = contained.get().getResource();
                    ClassificationType classificationType = classificationFromAuthor(resource1, containing);
                    if (classificationType != null) {
                        classificationType.setClassifiedObject(eo.getId());
                        classificationType.setId(rMgr.allocateSymbolicId());
                        eo.getClassification().add(classificationType);
                    }
                }
            }
        }
        if (dr.hasAuthenticator()) {
            tr = vale.addTr(new ValE("authenticator"));
            ResourceWrapper containing = new ResourceWrapper();
            containing.setResource(dr);
            Reference reference = dr.getAuthenticator();
            Optional<ResourceWrapper> contained = rMgr.resolveReference(containing, new Ref(reference.getReference()), new ResolverConfig().containedRequired(), tr);
            if (contained.isPresent()) {
                IBaseResource resource1 = contained.get().getResource();
                ClassificationType classificationType = classificationFromAuthor(resource1, containing);
                if (classificationType != null) {
                    classificationType.setId(rMgr.allocateSymbolicId());
                    for (SlotType1 slot1 : classificationType.getSlot()) {
                        if ("authorPerson".equals(slot1.getName())) {
                            if (!slot1.getValueList().getValue().isEmpty()) {
                                String auth = slot1.getValueList().getValue().get(0);
                                SlotType1 legalAuthenticator = new SlotType1();
                                legalAuthenticator.setName("legalAuthenticator");
                                ValueListType valueListType = new ValueListType();
                                legalAuthenticator.setValueList(valueListType);
                                valueListType.getValue().add(auth);
                                eo.getSlot().add(legalAuthenticator);
                            }
                        }
                    }
                }
            }
        }
        if (attachment.hasUrl()) {
            String url = attachment.getUrl();
            ResourceWrapper resourceWrapper = new ResourceWrapper();
            resourceWrapper.setRef(new Ref(url));
            Optional<ResourceWrapper> binary = rMgr.resolveReference(resourceWrapper, new Ref(url), new ResolverConfig(), vale);
            if (!binary.isPresent()) {
                vale.add(new ValE("Document(Binary) is not retrievable").asError());
                return eo;
            }
            if (!(binary.get().getResource() instanceof Binary)) {
                vale.add(new ValE("Document(Binary) is not a Binary (" + binary.get().getClass().getSimpleName() + " instead").asError());
                return eo;
            }
            Binary theBinary = (Binary) binary.get().getResource();
            eo.setMimeType(theBinary.getContentType());
            byte[] data = theBinary.getData();
            documentContents.put(eo.getId(), data);
        }
        if (attachment.hasContentType())
            eo.setMimeType(attachment.getContentType());
        return eo;
    }

    private void addCreationTime(ExtrinsicObjectType eo, Date creation) {
        String creationTime = translateDateTime(creation);
        addSlot(eo, "creationTime", creationTime);
    }

    private void addDescription(RegistryObjectType eo, String description) {
        LocalizedStringType lst = new LocalizedStringType();
        lst.setValue(description);
        InternationalStringType ist = new InternationalStringType();
        ist.getLocalizedString().add(lst);
        eo.setDescription(ist);
    }

    private ClassificationType classificationFromAuthor(IBaseResource resource1, ResourceWrapper containing) {
        if (resource1 instanceof Practitioner) {
            Practitioner practitioner = (Practitioner) resource1;
            ClassificationType classificationType = new Author().practitionerToClassification(practitioner);
            classificationType.setClassificationScheme("urn:uuid:93606bcf-9494-43ec-9b4e-a7748d1a838d");
            return classificationType;
        } else if (resource1 instanceof PractitionerRole) {
            PractitionerRole practitionerRole = (PractitionerRole) resource1;
            if (practitionerRole.hasPractitioner()) {
                Optional<ResourceWrapper> contained2 = rMgr.resolveReference(containing, new Ref(practitionerRole.getPractitioner()), new ResolverConfig().containedRequired());
                if (contained2.isPresent() && contained2.get().getResource() instanceof Practitioner) {
                    Practitioner practitioner = (Practitioner) contained2.get().getResource();
                    Author author = new Author();
                    for (CodeableConcept cc : practitionerRole.getCode()) {
                        AuthorRole role = new AuthorRole(cc);
                        author.getAuthorRoles().add(role);
                    }
                    ClassificationType classificationType = author.practitionerToClassification(practitioner);
                    classificationType.setClassificationScheme("urn:uuid:93606bcf-9494-43ec-9b4e-a7748d1a838d");
                    return classificationType;
                }
            }
        } else if (resource1 instanceof Organization) {
            Organization organization = (Organization) resource1;
            Author author = new Author();
            author.setVal(val);
            ClassificationType classificationType = author.organizationToClassification(organization);
            if (classificationType != null) {
                classificationType.setClassificationScheme("urn:uuid:93606bcf-9494-43ec-9b4e-a7748d1a838d");
                return classificationType;
            }
        } else {
            val.add(new ValE("Cannot process author of type " + resource1.getClass().getSimpleName()).asWarning());
        }
        return null;
    }

    private String translateDateTime(Date date) {
        return DateTransform.fhirToDtm(date);
    }

    public void addName(RegistryObjectType eo, String name) {
        LocalizedStringType lst = new LocalizedStringType();
        lst.setValue(name);
        InternationalStringType ist = new InternationalStringType();
        ist.getLocalizedString().add(lst);
        eo.setName(ist);
    }

    public void addSlot(RegistryObjectType registryObject, String name, String value) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(value);
        SlotType1 slot = new SlotType1();
        slot.setName(name);
        ValueListType valueList = new ValueListType();
        valueList.getValue().add(value);
        slot.setValueList(valueList);
        registryObject.getSlot().add(slot);
    }

    public void addSlot(RegistryObjectType registryObject, String name, List<String> values) {
        Objects.requireNonNull(name);
        SlotType1 slot = new SlotType1();
        slot.setName(name);
        ValueListType valueList = new ValueListType();
        for (String value : values)
            valueList.getValue().add(value);
        slot.setValueList(valueList);
        registryObject.getSlot().add(slot);
    }

    /**
     * Patient resources shall not be in the bundle so don't look there.  Must have fullUrl reference
     */

    private void addSourcePatientInfo(ExtrinsicObjectType eo, ResourceWrapper resource, Reference sourcePatient, ValE val) {
    }
        // TODO sourcePatientInfo is not populated
//    private void addSourcePatient(ExtrinsicObjectType eo, ResourceWrapper resource, Reference sourcePatient) {
//        if (!sourcePatient.reference)
//            return
//        val.add(new Val().msg("Resolve ${sourcePatient.reference} as SourcePatient"))
//        def extra = 'DocumentReference.context.sourcePatientInfo must reference Contained Patient resource with Patient.identifier.use element set to "usual"'
//        ResourceWrapper loadedPatient = rMgr.resolveReference(resource, new Ref(sourcePatient.reference), new ResolverConfig().containedRequired())
//        if (!loadedPatient.resource) {
//            val.err(new Val()
//            .msg("Cannot load resource at ${loadedPatient.url}"))
//            return
//        }
//
//        if (!(loadedPatient.resource instanceof Patient)) {
//            val.err(new Val()
//            .msg("Patient loaded from ${loadedPatient.url} returned a ${loadedPatient.resource.class.simpleName} instead"))
//            return
//        }
//
//        Patient patient = (Patient) loadedPatient.resource
//
//        // find identifier that aligns with required Assigning Authority
//        List<Identifier> identifiers = patient.getIdentifier()
//
//        String pid = findAcceptablePID(identifiers)
//        if (pid)
//            addSlot(builder, 'sourcePatientId', [pid])
//    }

    private String findAcceptablePID(List<Identifier> identifiers) {
        Objects.requireNonNull(assigningAuthorities);
        List<String> pids = identifiers.stream()
                .filter(identifier -> assigningAuthorities.check(unURN(identifier.getSystem())))
                .map(identifier -> identifier.getValue() + "^^^&" + unURN(identifier.getSystem()) + "&ISO")
                .collect(Collectors.toList());

        return (pids.isEmpty()) ? null : pids.get(0);
    }

    // TODO must be absolute reference
    // TODO official identifiers must be changed
    private void addSubject(RegistryObjectType ro, ResourceWrapper resource, Ref referenced, String scheme, String attName, ValE val) {

        Optional<ResourceWrapper> loadedResource = rMgr.resolveReference(resource, referenced, new ResolverConfig().externalRequired());
        boolean isLoaded = loadedResource.isPresent() && loadedResource.get().isLoaded();
        if (!isLoaded) {
            val.add(new ValE(resource + " makes reference to " + referenced + " which cannot be loaded").asError()
                    .add(new ValE("   All DocumentReference.subject and DocumentManifest.subject values shall be References to FHIR Patient Resources identified by an absolute external reference (URL).").asDoc())
                    .add(new ValE("3.65.4.1.2.2 Patient Identity").asDoc()));
            return;
        }
        if (!(loadedResource.get().getResource() instanceof Patient)) {
            val.add(new ValE(resource + " points to a " + loadedResource.get().getResource().getClass().getSimpleName() + " - it must be a Patient").asError()
                    .add(new ValE("3.65.4.1.2.2 Patient Identity").asDoc()));
            return;
        }

        Patient patient = (Patient) loadedResource.get().getResource();

        List<Identifier> identifiers = patient.getIdentifier();
        String pid = findAcceptablePID(identifiers);

        if (pid != null)
            addExternalIdentifier(ro, scheme, pid, rMgr.allocateSymbolicId(), resource.getAssignedId(), attName, null);
    }

    public void addExternalIdentifier(RegistryObjectType ro, String scheme, String value, String id, String registryObject, String name, IdBuilder idBuilder) {
        val.add(new ValE("ExternalIdentifier " + scheme));
        //List<ExternalIdentifierType> eits = ro.getExternalIdentifier();
        if (idBuilder != null)
            value = idBuilder.allocate(value); // maybe override
        ExternalIdentifierType eit = new ExternalIdentifierType();
        eit.setIdentificationScheme(scheme);
        eit.setId(id);
        eit.setRegistryObject(registryObject);
        eit.setValue(value);
        eit.setObjectType("urn:oasis:names:tc:ebxml-regrep:ObjectType:RegistryObject:ExternalIdentifier");

        InternationalStringType ist = new InternationalStringType();
        LocalizedStringType lst = new LocalizedStringType();
        lst.setValue(name);
        ist.getLocalizedString().add(lst);
        eit.setName(ist);
        ro.getExternalIdentifier().add(eit);
    }

    // TODO - no profile guidance on how to convert coding.system URL to existing OIDs

    public void addClassificationFromCodeableConcept(RegistryObjectType ro, CodeableConcept cc, String scheme, String classifiedObjectId, ValE val) {
        List<Coding> coding = cc.getCoding();
        addClassificationFromCoding(ro, coding.get(0), scheme, classifiedObjectId, val);
    }

    private void addClassificationFromCoding(RegistryObjectType ro, Coding coding, String scheme, String classifiedObjectId, ValE val) {
        Objects.requireNonNull(codeTranslator);
        Objects.requireNonNull(val);
        Objects.requireNonNull(rMgr);
        Optional<Code> systemCodeOpt = codeTranslator.findCodeByClassificationAndSystem(scheme, coding.getSystem(), coding.getCode());
        if (systemCodeOpt.isPresent()) {
            Code systemCode = systemCodeOpt.get();
            String displayName = coding.getDisplay() == null ? systemCode.getDisplay() : coding.getDisplay();
            addClassification(ro, scheme, rMgr.allocateSymbolicId(), classifiedObjectId, coding.getCode(), systemCode.getCodingScheme(), displayName);
        } else {
            Optional<gov.nist.asbestos.asbestorCodesJaxb.CodeType> type = codeTranslator.findCodeTypeForScheme(scheme);
            String schemeName = (type.isPresent()) ? type.get().getName() : scheme;
            val.add(new ValE("Cannot find translation for code " + coding.getSystem() + "|" + coding.getCode() + " as part of attribute " + schemeName + " in configured codes.xml file").asError());
        }
    }

    /**
     * add external classification (see ebRIM for definition)
     * @param scheme
     * @param id
     * @param registryObject
     * @param value
     * @param codeScheme
     * @param displayName
     * @return
     */
    private void addClassification(RegistryObjectType ro, String scheme, String id, String registryObject, String value, String codeScheme, String displayName) {
        ClassificationType ct = new ClassificationType();
        ct.setClassificationScheme(scheme);
        ct.setId(id);
        ct.setObjectType("urn:oasis:names:tc:ebxml-regrep:ObjectType:RegistryObject:Classification");
        ct.setNodeRepresentation(value);
        ct.setClassifiedObject(registryObject);
        addSlot(ct, "codingScheme", codeScheme);
        addName(ct, displayName);
        ro.getClassification().add(ct);
    }

    private void addClassification(RegistryObjectType ro, String node, String id, String classifiedObject) {
        val.add(new ValE("Classification " + node));
        ClassificationType ct = new ClassificationType();
        ct.setClassifiedObject(classifiedObject);
        ct.setClassificationNode(node);
        ct.setId(id);
        ct.setObjectType("urn:oasis:names:tc:ebxml-regrep:ObjectType:RegistryObject:Classification");
        ro.getClassification().add(ct);
    }


//    private addDocument(MarkupBuilder builder, String drId, String contentId) {
//        val.add(new Val().msg("Attach Document ${drId}"))
//        builder.Document(id:drId, xmlns: 'urn:ihe:iti:xds-b:2007') {
//            Include(href: "cid:${contentId}", xmlns: 'http://www.w3.org/2004/08/xop/include')
//        }
//    }

//    static List<MhdIdentifier> getIdentifiers(IBaseResource resource) {
//        assert resource instanceof DocumentManifest || resource instanceof DocumentReference
//
//        List<Identifier> identifiers = (resource instanceof DocumentManifest) ?
//                ((DocumentManifest) resource).identifier :
//                ((DocumentReference) resource).identifier
//        identifiers.collect { Identifier ident -> new MhdIdentifier(ident)}
//    }

    private static String unURN(String uuid) {
        if (uuid.startsWith("urn:uuid:")) return uuid.substring(9);
        if (uuid.startsWith("urn:oid:")) return uuid.substring(8);
        return uuid;
    }

    private void scanBundleForAcceptability(Bundle bundle, ResourceMgr rMgr) {
        if (bundle.getMeta().getProfile().size() != 1)
            val.add(new ValE("No profile declaration present in bundle").asError()
                    .add(new ValE("3.65.4.1.2.1 Bundle Resources").asDoc()));
        CanonicalType bundleProfile = bundle.getMeta().getProfile().get(0);
        if (!profiles.contains(bundleProfile.asStringValue()))
            val.add(new ValE("Do not understand profile declared in bundle - " + bundleProfile).asError()
                    .add(new ValE("3.65.4.1.2.1 Bundle Resources").asDoc()));

        for (ResourceWrapper res : rMgr.getBundleResources()) {
            if (!acceptableResourceTypes.contains(res.getResource().getClass()))
                val.add(new ValE("Resource type ${resource.resource.class.simpleName} is not part of MHD and will be ignored").asWarning()
                        .add(new ValE(mhdProfileRef).asDoc()));
        }

    }

    @Override
    public void setVal(Val val) {
        this.val = val;
        if (rMgr != null)
            rMgr.setVal(val);
    }

    public BundleToRegistryObjectList setResourceMgr(ResourceMgr rMgr) {
        this.rMgr = rMgr;
        return this;
    }

    public BundleToRegistryObjectList setAssigningAuthorities(AssigningAuthorities assigningAuthorities) {
        this.assigningAuthorities = assigningAuthorities;
        return this;
    }

    public BundleToRegistryObjectList setCodeTranslator(CodeTranslator codeTranslator) {
        this.codeTranslator = codeTranslator;
        return this;
    }

    public BundleToRegistryObjectList setBundleProfile(CanonicalType bundleProfile) {
        return this;
    }

    public byte[] getDocumentContents(String id) {
        return documentContents.get(id);
    }

    public Map<String,  byte[]> getDocumentContents() {
        return documentContents;
    }

    public void setIdBuilder(IdBuilder idBuilder) {
        this.idBuilder = idBuilder;
    }
}
