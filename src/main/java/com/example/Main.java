package com.example;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Patient;
import ca.uhn.fhir.jpa.ips.generator.IpsGeneratorSvcImpl;

public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar my-hapi-fhir-app.jar <path-to-json-folder>");
            System.exit(1);
        }

        String jsonFolderPath = args[0];

        LocalFileIpsGenerationStrategy strategy = new LocalFileIpsGenerationStrategy(jsonFolderPath);
        // strategy.initialize();
        Patient patient = (Patient) strategy.fetchPatientResource();
        IIdType patientId = patient.getIdElement();
        IpsGeneratorSvcImpl ipsGeneratorSvc = new IpsGeneratorSvcImpl(FhirContext.forR4(), strategy);
        IBaseBundle ipsBundle = ipsGeneratorSvc.generateIps(new SimpleRequestDetails(null, "https://example.org/fhir"), patientId, jsonFolderPath);
        
        // Output the IPS Bundle as JSON
        FhirContext fhirContext = FhirContext.forR4();
        String bundleJson = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(ipsBundle);
        System.out.println(bundleJson);
    }
}
