package com.example;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import ca.uhn.fhir.jpa.ips.api.*;
import ca.uhn.fhir.jpa.ips.strategy.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class LocalFileIpsGenerationStrategy extends BaseIpsGenerationStrategy {

    private static final String SECTION_SYSTEM_LOINC = "http://loinc.org";
    private static final Map<String, String> SECTION_CODES = Map.ofEntries(
        Map.entry("Allergies and Intolerances", "48765-2"),
        Map.entry("Medication List", "10160-0"),
        Map.entry("Problem List", "11450-4"),
        Map.entry("History of Immunizations", "11369-6"),
        Map.entry("History of Procedures", "47519-4"),
        Map.entry("Medical Devices", "46264-8"),
        Map.entry("Diagnostic Results", "30954-2"),
        Map.entry("Vital Signs", "8716-3"),
        Map.entry("Pregnancy Information", "10162-6"),
        Map.entry("Social History", "29762-2"),
        Map.entry("History of Past Illness", "11348-0"),
        Map.entry("Functional Status", "47420-5"),
        Map.entry("Plan of Care", "18776-5"),
        Map.entry("Advance Directives", "42348-3")
    );

    private final String directoryPath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Class<? extends IBaseResource>, List<IBaseResource>> resourceMap = new HashMap<>();

    public LocalFileIpsGenerationStrategy(String directoryPath) {
        this.directoryPath = directoryPath;
        loadResourcesFromDirectory();
    }

    @Override
    public String getBundleProfile() {
        return "http://hl7.org/fhir/uv/ips/StructureDefinition-ips";
    }

    @Override
    public void initialize() {
        addSections();
    }

    private void addSections() {
        addSection("Allergies and Intolerances", AllergyIntolerance.class, new AllergyIntoleranceNoInfoR4Generator());
        addSection("Medication List", List.of(MedicationStatement.class, MedicationRequest.class, MedicationAdministration.class, MedicationDispense.class), new MedicationNoInfoR4Generator());
        addSection("Problem List", Condition.class, new ProblemNoInfoR4Generator());
        addSection("History of Immunizations", Immunization.class, null);
        addSection("History of Procedures", Procedure.class, null);
        addSection("Medical Devices", DeviceUseStatement.class, null);
        addSection("Diagnostic Results", List.of(DiagnosticReport.class, Observation.class), null);
        addSection("Vital Signs", Observation.class, null);
        // addSection("Pregnancy Information", Observation.class, null);
        addSection("Social History", Observation.class, null);
        addSection("History of Past Illness", Condition.class, null);
        addSection("Functional Status", ClinicalImpression.class, null);
        addSection("Plan of Care", CarePlan.class, null);
        addSection("Advance Directives", Consent.class, null);
    }

    private void addSection(String title, Class<? extends IBaseResource> resourceType, @Nullable INoInfoGenerator noInfoGenerator) {
        addSection(title, List.of(resourceType), noInfoGenerator);
    }

    private void addSection(String title, List<Class<? extends IBaseResource>> resourceTypes, @Nullable INoInfoGenerator noInfoGenerator) {
        Section section = createSection(title, SECTION_CODES.get(title), title, resourceTypes, noInfoGenerator);
        ISectionResourceSupplier supplier = getSectionResourceSupplier(section);
        System.err.println("Adding section: " + title + section.toString());
        addSection(section, supplier);
    }

    @Nonnull
    private Section createSection(String title, String code, String display, List<Class<? extends IBaseResource>> resourceTypes, @Nullable INoInfoGenerator noInfoGenerator) {
        Section.SectionBuilder builder = Section.newBuilder()
                .withTitle(title)
                .withSectionSystem(SECTION_SYSTEM_LOINC)
                .withSectionCode(code)
                .withSectionDisplay(display)
                .withProfile("http://hl7.org/fhir/uv/ips/StructureDefinition-Composition-uv-ips-definitions.html#Composition.section:" + title.replace(" ", ""));
        resourceTypes.forEach(builder::withResourceType);
        if (noInfoGenerator != null) {
            builder.withNoInfoGenerator(noInfoGenerator);
        }
        System.err.println("Adding section: " + title + code + display +  builder.toString());
        return builder.build();
    }

    @Nonnull
    @Override
    public ISectionResourceSupplier getSectionResourceSupplier(@Nonnull Section theSection) {
        return new ISectionResourceSupplier() {
            @Override
            public <T extends IBaseResource> List<ResourceEntry> fetchResourcesForSection(IpsContext ipsContext, IpsSectionContext<T> sectionContext, RequestDetails requestDetails) {
                List<ResourceEntry> resourceEntries = new ArrayList<>();
                for (Class<? extends IBaseResource> resourceType : theSection.getResourceTypes()) {
                    List<IBaseResource> resources = resourceMap.getOrDefault(resourceType, List.of());
                    for (IBaseResource resource : resources) {
                        System.err.println("Adding resource: " + resource.getIdElement().getValue());
                        resourceEntries.add(new ResourceEntry(resource, InclusionTypeEnum.PRIMARY_RESOURCE));
                    }
                }
                return resourceEntries;
            }
        };
    }

    @Override
    public IBaseResource createAuthor() {
        Organization author = new Organization();
        author.setName("Generated by LocalFileIpsGenerationStrategy");
        return author;
    }

    @Nonnull
    @Override
    public IBaseResource fetchPatient(IIdType thePatientId, RequestDetails theRequestDetails) throws ResourceNotFoundException {
        return fetchPatientResource();
    }

    @Nonnull
    @Override
    public IBaseResource fetchPatient(TokenParam thePatientIdentifier, RequestDetails theRequestDetails) {
        return fetchPatientResource();
    }

    @Override
    public void postManipulateIpsBundle(IBaseBundle theBundle) {
        // Perform any post-processing manipulations on the bundle
    }

    private void loadResourcesFromDirectory() {
        File directory = new File(directoryPath);
        if (!directory.isDirectory()) {
            throw new IllegalArgumentException("Provided path is not a directory: " + directoryPath);
        }

        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            throw new IllegalArgumentException("Failed to list files in directory: " + directoryPath);
        }

        for (File file : files) {
            try {
                JsonNode rootNode = objectMapper.readTree(file);
                if (rootNode.isArray()) {
                    for (JsonNode node : rootNode) {
                        IBaseResource resource = FhirContext.forR4().newJsonParser().parseResource(node.toString());
                        addResourceToMap(resource);
                    }
                } else if (rootNode.has("resourceType") && "Bundle".equals(rootNode.get("resourceType").asText())) {
                    JsonNode entries = rootNode.get("entry");
                    if (entries != null && entries.isArray()) {
                        for (JsonNode entry : entries) {
                            JsonNode resourceNode = entry.get("resource");
                            if (resourceNode != null) {
                                IBaseResource resource = FhirContext.forR4().newJsonParser().parseResource(resourceNode.toString());
                                addResourceToMap(resource);
                            }
                        }
                    }
                } else {
                    IBaseResource resource = FhirContext.forR4().newJsonParser().parseResource(rootNode.toString());
                    addResourceToMap(resource);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file: " + file.getPath(), e);
            }
        }
    }

    private void addResourceToMap(IBaseResource resource) {
        resourceMap.computeIfAbsent(resource.getClass(), k -> new ArrayList<>()).add(resource);
    }

    protected Patient fetchPatientResource() {
        List<IBaseResource> patients = resourceMap.get(Patient.class);
        if (patients == null || patients.isEmpty()) {
            throw new ResourceNotFoundException("Patient resource not found");
        }
        return (Patient) patients.get(0);
    }
}
