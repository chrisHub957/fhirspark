package fhirspark;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import fhirspark.restmodel.TherapyRecommendation;
import fhirspark.restmodel.Treatment;
import fhirspark.restmodel.Reasoning;
import fhirspark.restmodel.Therapy;

public class JsonFhirMapper {

    FhirContext ctx = FhirContext.forR4();
    IGenericClient client = ctx.newRestfulGenericClient("http://localhost:8080/hapi-fhir-jpaserver/fhir/");
    ObjectMapper objectMapper = new ObjectMapper(new JsonFactory());

    public void fromJson(String jsonString) throws JsonMappingException, JsonProcessingException {

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTION);

        Therapy therapy = this.objectMapper.readValue(jsonString, Therapy.class);

        Patient patient = getOrCreatePatient(bundle, therapy.getId());

        for (TherapyRecommendation therapyRecommendation : therapy.getTherapyRecommendations()) {
            CarePlan carePlan = new CarePlan();
            carePlan.setSubject(new Reference(patient));

            carePlan.addIdentifier(
                    new Identifier().setSystem("cbioportal").setValue(therapyRecommendation.getId()));
            List<Annotation> notes = new ArrayList<Annotation>();
            for (String comment : therapyRecommendation.getComment())
                notes.add(new Annotation().setText(comment));
            carePlan.setNote(notes);

            bundle.addEntry().setFullUrl(carePlan.getIdElement().getValue()).setResource(carePlan).getRequest()
                    .setUrl("CarePlan").setMethod(Bundle.HTTPVerb.POST);
        }

        Bundle resp = client.transaction().withBundle(bundle).execute();

        // Log the response
        System.out.println(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(resp));

    }

    public String toJson(String patientId) throws JsonProcessingException {
        Therapy therapy = new Therapy();
        List<TherapyRecommendation> therapyRecommendations = new ArrayList<TherapyRecommendation>();

        Bundle bPatient = (Bundle) client.search().forResource(Patient.class)
                .where(new TokenClientParam("identifier").exactly().systemAndCode("cbioportal", patientId))
                .prettyPrint().execute();

        Patient patient = (Patient) bPatient.getEntryFirstRep().getResource();

        if (patient == null)
            return "{}";

        Bundle bCarePlans = (Bundle) client.search().forResource(CarePlan.class)
                .where(new ReferenceClientParam("subject").hasId(patient.getIdElement())).prettyPrint().execute();

        List<BundleEntryComponent> carePlans = bCarePlans.getEntry();

        if(carePlans.size() > 0) {
            therapy.setId(patientId);
            therapy.setTherapyRecommendations(therapyRecommendations);
        }

        for (int i = 0; i < carePlans.size(); i++) {
            CarePlan carePlan = (CarePlan) carePlans.get(i).getResource();
            TherapyRecommendation therapyRecommendation = new TherapyRecommendation();
            therapyRecommendations.add(therapyRecommendation);

            therapyRecommendation.setId(carePlan.getIdentifierFirstRep().getValue());
            List<String> comments = new ArrayList<String>();
            for (Annotation annotation : carePlan.getNote())
                comments.add(annotation.getText());
            therapyRecommendation.setComment(comments);

            List<Treatment> treatments = new ArrayList<Treatment>();
            therapyRecommendation.setTreatments(treatments);

            Reasoning reasoning = new Reasoning();
            therapyRecommendation.setReasoning(reasoning);

            List<fhirspark.restmodel.Reference> references = new ArrayList<fhirspark.restmodel.Reference>();
            therapyRecommendation.setReferences(references);
        }

        return this.objectMapper.writeValueAsString(therapy);
    }

    private Patient getOrCreatePatient(Bundle b, String patientId) {

        Bundle b2 = (Bundle) client.search().forResource(Patient.class)
                .where(new TokenClientParam("identifier").exactly().systemAndCode("cbioportal", patientId))
                .prettyPrint().execute();

        Patient p = (Patient) b2.getEntryFirstRep().getResource();

        if (p != null && p.getIdentifierFirstRep().hasValue()) {
            return p;
        } else {

            Bundle patientBundle = new Bundle();
            patientBundle.setType(BundleType.TRANSACTION);

            Patient patient = new Patient();
            patient.addIdentifier(new Identifier().setSystem("cbioportal").setValue(patientId));
            patientBundle.addEntry().setResource(patient).getRequest().setMethod(Bundle.HTTPVerb.POST);

            Bundle resp = client.transaction().withBundle(patientBundle).execute();

            return getOrCreatePatient(b, patientId);
        }

    }

}