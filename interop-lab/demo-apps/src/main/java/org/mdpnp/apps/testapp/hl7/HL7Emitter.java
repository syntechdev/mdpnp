package org.mdpnp.apps.testapp.hl7;

import static ca.uhn.fhir.model.dstu2.valueset.AdministrativeGenderEnum.MALE;
import static ca.uhn.fhir.model.dstu2.valueset.IdentifierUseEnum.OFFICIAL;
import ice.AlarmSettings;
import ice.AlarmSettingsDataReader;
import ice.Alert;
import ice.AlertDataReader;
import ice.Numeric;
import ice.NumericDataReader;

import java.io.IOException;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.rtiapi.data.AlarmSettingsInstanceModel;
import org.mdpnp.rtiapi.data.AlarmSettingsInstanceModelImpl;
import org.mdpnp.rtiapi.data.AlarmSettingsInstanceModelListener;
import org.mdpnp.rtiapi.data.AlertInstanceModel;
import org.mdpnp.rtiapi.data.AlertInstanceModelImpl;
import org.mdpnp.rtiapi.data.AlertInstanceModelListener;
import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.InstanceModel;
import org.mdpnp.rtiapi.data.ListenerList;
import org.mdpnp.rtiapi.data.NumericInstanceModel;
import org.mdpnp.rtiapi.data.NumericInstanceModelListener;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.model.dstu2.composite.QuantityDt;
import ca.uhn.fhir.model.dstu2.composite.ResourceReferenceDt;
import ca.uhn.fhir.model.dstu2.resource.Device;
import ca.uhn.fhir.model.dstu2.resource.DeviceMetric;
import ca.uhn.fhir.model.dstu2.resource.Observation;
import ca.uhn.fhir.model.dstu2.resource.Patient;
import ca.uhn.fhir.model.primitive.DateTimeDt;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.IGenericClient;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.app.Connection;
import ca.uhn.hl7v2.app.Initiator;
import ca.uhn.hl7v2.llp.LLPException;
import ca.uhn.hl7v2.model.DataTypeException;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v26.datatype.NM;
import ca.uhn.hl7v2.model.v26.datatype.TX;
import ca.uhn.hl7v2.model.v26.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v26.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v26.group.ORU_R01_PATIENT;
import ca.uhn.hl7v2.model.v26.message.ORU_R01;
import ca.uhn.hl7v2.model.v26.segment.MSH;
import ca.uhn.hl7v2.model.v26.segment.OBX;
import ca.uhn.hl7v2.model.v26.segment.PID;
import ca.uhn.hl7v2.parser.Parser;

import com.rti.dds.infrastructure.SequenceNumber_t;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.Subscriber;

public class HL7Emitter {
    public enum Type {
        FHIR_DSTU2,
        V26,
    }
    
    
    protected static final Logger log = LoggerFactory.getLogger(HL7Emitter.class);
    
    private final EventLoop eventLoop;
    private final Subscriber subscriber;
    private final DeviceListModel deviceListModel;
    private final HapiContext hl7Context;
    protected final FhirContext fhirContext;
    
    protected Connection hl7Connection;
    protected IGenericClient fhirClient;
    
    private final ListenerList<LineEmitterListener> listeners = new ListenerList<LineEmitterListener>(LineEmitterListener.class);
    private final ListenerList<StartStopListener> ssListeners = new ListenerList<StartStopListener>(StartStopListener.class);
    
    public HL7Emitter(final Subscriber subscriber, final EventLoop eventLoop, final NumericInstanceModel numericInstanceModel, final DeviceListModel deviceListModel) {
        this.subscriber = subscriber;
        this.eventLoop = eventLoop;
        this.deviceListModel = deviceListModel;
        hl7Context = new DefaultHapiContext();
        fhirContext = FhirContext.forDstu2();
        this.numericInstanceModel = numericInstanceModel;
        patientAlertInstanceModel = new AlertInstanceModelImpl(ice.PatientAlertTopic.VALUE);
        technicalAlertInstanceModel = new AlertInstanceModelImpl(ice.TechnicalAlertTopic.VALUE);
        alarmSettingsInstanceModel = new AlarmSettingsInstanceModelImpl(ice.AlarmSettingsTopic.VALUE);
        
        patientAlertInstanceModel.addListener(patientAlertListener);
        technicalAlertInstanceModel.addListener(technicalAlertListener);
        alarmSettingsInstanceModel.addListener(alarmSettingsListener);
    }
    
    private final NumericInstanceModel numericInstanceModel;
    private final AlertInstanceModel patientAlertInstanceModel, technicalAlertInstanceModel;
    private final AlarmSettingsInstanceModel alarmSettingsInstanceModel;
    private Type type;
    
    public void start(final String host, final int port, final Type type) {
        this.type = type;
        numericInstanceModel.iterateAndAddListener(numericListener);
        patientAlertInstanceModel.start(subscriber, eventLoop, QosProfiles.ice_library, QosProfiles.state);
        technicalAlertInstanceModel.start(subscriber, eventLoop, QosProfiles.ice_library, QosProfiles.state);
        alarmSettingsInstanceModel.start(subscriber, eventLoop, QosProfiles.ice_library, QosProfiles.state);
        
        log.debug("Started NumericInstanceModel");
        if(host != null && !host.isEmpty()) {
            if(Type.V26.equals(type)) {
                try {
                    
                    hl7Connection = hl7Context.newClient(host, port, false);
                    ssListeners.fire(started);
                    
                } catch (HL7Exception e) {
                    log.error("", e);
                    stop();
                } catch(RuntimeException re) {
                    log.error("", re);
                    stop();
                }
            } else if(Type.FHIR_DSTU2.equals(type)) {
                fhirClient = fhirContext.newRestfulGenericClient(host);
                ssListeners.fire(started);
            }
        } else {
            // We'll make it ok to start with no external connection
            // just to demo the ability to compose HL7 messages
            ssListeners.fire(started);
        }
    }
    public void stop() {
        numericInstanceModel.removeListener(numericListener);
        patientAlertInstanceModel.stop();
        technicalAlertInstanceModel.stop();
        alarmSettingsInstanceModel.stop();
        ssListeners.fire(stopped);
        if(hl7Connection != null) {
            hl7Connection.close();
            hl7Connection = null;
        }
        if(fhirClient != null) {
            // TODO is there an active connection to disconnect?
            fhirClient = null;
        }
        
    }
    
    static class DispatchStartStop implements ListenerList.Dispatcher<StartStopListener> {
        final boolean started;
        DispatchStartStop(final boolean started) {
            this.started = started;
        }
        
        @Override
        public void dispatch(StartStopListener l) {
            if(started) {
                l.started();
            } else {
                l.stopped();
            }
        }
        
    }
    private final static DispatchStartStop started = new DispatchStartStop(true);
    private final static DispatchStartStop stopped = new DispatchStartStop(false);

    static class DispatchLine implements ListenerList.Dispatcher<LineEmitterListener> {
        private final String line;
        public DispatchLine(final String line) {
            this.line = line;
        }
        @Override
        public void dispatch(LineEmitterListener l) {
            l.newLine(line);
        }
        
    }
    
    public NumericInstanceModel getNumericInstanceModel() {
        return numericInstanceModel;
    }
    
    public void addLineEmitterListener(LineEmitterListener listener) {
        listeners.addListener(listener);
    }
    public void removeLineEmitterListener(LineEmitterListener listener) {
        listeners.removeListener(listener);
    }
    
    public void addStartStopListener(StartStopListener listener) {
        ssListeners.addListener(listener);
    }
    
    public void removeStartStopListener(StartStopListener listener) {
        ssListeners.removeListener(listener);
    }
    
    final AlertInstanceModelListener patientAlertListener = new AlertInstanceModelListener() {

        @Override
        public void instanceAlive(InstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert data, SampleInfo sampleInfo) {
        }

        @Override
        public void instanceNotAlive(InstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert keyHolder, SampleInfo sampleInfo) {
        }

        @Override
        public void instanceSample(InstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert data, SampleInfo sampleInfo) {
            if(Type.V26.equals(type)) {
                try {
                    
                    
                    ORU_R01 r01 = new ORU_R01();
                    // ORU is an observation
                    // Event R01 is an unsolicited observation message
                    // "T" for Test, "P" for Production, etc.
                    r01.initQuickstart("ORU", "R01", "T");
            
                    // Populate the MSH Segment
                    MSH mshSegment = r01.getMSH();
                    mshSegment.getSendingApplication().getNamespaceID().setValue("ICE");
                    mshSegment.getSequenceNumber().setValue("123");
            
                    // Populate the PID Segment
                    ORU_R01_PATIENT patient = r01.getPATIENT_RESULT().getPATIENT();
                    PID pid = patient.getPID();
                    pid.getPatientName(0).getFamilyName().getSurname().setValue("Doe");
                    pid.getPatientName(0).getGivenName().setValue("John");
                    pid.getPatientIdentifierList(0).getIDNumber().setValue("123456");
            
                    ORU_R01_ORDER_OBSERVATION orderObservation = r01.getPATIENT_RESULT().getORDER_OBSERVATION();
            
                    orderObservation.getOBR().getObr7_ObservationDateTime().setValueToSecond(new Date());
                    
                    ORU_R01_OBSERVATION observation = orderObservation.getOBSERVATION(0);
                    
                    
                    // Populate the first OBX
                    OBX obx = observation.getOBX();
                    //obx.getSetIDOBX().setValue("1");
                    obx.getObservationIdentifier().getIdentifier().setValue(data.identifier);
                    obx.getObservationIdentifier().getText().setValue("");
                    obx.getObservationIdentifier().getCwe3_NameOfCodingSystem().setValue("Unknown");
                    obx.getObservationSubID().setValue("0");
    //                obx.getUnits().getIdentifier().setValue("0004-0aa0");
    //                obx.getUnits().getText().setValue("bpm");
    //                obx.getUnits().getCwe3_NameOfCodingSystem().setValue("MDIL");
                    obx.getObservationResultStatus().setValue("F");
            
                    // The first OBX has a value type of CE. So first, we populate OBX-2 with "CE"...
                    obx.getValueType().setValue("TX");
            
                    // "TX" is ?
                    TX tx = new TX(r01);
                    tx.setValue(data.text);
            
                    obx.getObservationValue(0).setData(tx);
            
                    Parser parser = hl7Context.getPipeParser();
    
                    String encodedMessage = parser.encode(r01);
                    listeners.fire(new DispatchLine(encodedMessage));
                    
                    
                    // Now, let's encode the message and look at the output
                    Connection hapiConnection = HL7Emitter.this.hl7Connection;
                    if(null != hapiConnection) {
    
            
                        Initiator initiator = hapiConnection.getInitiator();
                        Message response = initiator.sendAndReceive(r01);
                        String responseString = parser.encode(response);
                        log.debug("Received Response:"+responseString);
                        
                    }
                } catch (DataTypeException e) {
                    log.error("", e);
                } catch (HL7Exception e) {
                    log.error("", e);
                } catch (IOException e) {
                    log.error("", e);
                } catch (LLPException e) {
                    log.error("", e);
                } finally {
                    
                }
            }
        }
        
    };
    
    final AlertInstanceModelListener technicalAlertListener = new AlertInstanceModelListener() {

        @Override
        public void instanceAlive(InstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert data, SampleInfo sampleInfo) {
        }

        @Override
        public void instanceNotAlive(InstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert keyHolder, SampleInfo sampleInfo) {
        }

        @Override
        public void instanceSample(InstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert data, SampleInfo sampleInfo) {
            if(Type.V26.equals(type)) {
                try {
                    
                    
                    ORU_R01 r01 = new ORU_R01();
                    // ORU is an observation
                    // Event R01 is an unsolicited observation message
                    // "T" for Test, "P" for Production, etc.
                    r01.initQuickstart("ORU", "R01", "T");
            
                    // Populate the MSH Segment
                    MSH mshSegment = r01.getMSH();
                    mshSegment.getSendingApplication().getNamespaceID().setValue("ICE");
                    mshSegment.getSequenceNumber().setValue("123");
            
                    // Populate the PID Segment
                    ORU_R01_PATIENT patient = r01.getPATIENT_RESULT().getPATIENT();
                    PID pid = patient.getPID();
                    pid.getPatientName(0).getFamilyName().getSurname().setValue("Doe");
                    pid.getPatientName(0).getGivenName().setValue("John");
                    pid.getPatientIdentifierList(0).getIDNumber().setValue("123456");
            
                    ORU_R01_ORDER_OBSERVATION orderObservation = r01.getPATIENT_RESULT().getORDER_OBSERVATION();
            
                    orderObservation.getOBR().getObr7_ObservationDateTime().setValueToSecond(new Date());
                    
                    ORU_R01_OBSERVATION observation = orderObservation.getOBSERVATION(0);
                    
                    
                    // Populate the first OBX
                    OBX obx = observation.getOBX();
                    //obx.getSetIDOBX().setValue("1");
                    obx.getObservationIdentifier().getIdentifier().setValue(data.identifier);
                    obx.getObservationIdentifier().getText().setValue("");
                    obx.getObservationIdentifier().getCwe3_NameOfCodingSystem().setValue("Unknown");
                    obx.getObservationSubID().setValue("0");
    //                obx.getUnits().getIdentifier().setValue("0004-0aa0");
    //                obx.getUnits().getText().setValue("bpm");
    //                obx.getUnits().getCwe3_NameOfCodingSystem().setValue("MDIL");
                    obx.getObservationResultStatus().setValue("F");
            
                    // The first OBX has a value type of CE. So first, we populate OBX-2 with "CE"...
                    obx.getValueType().setValue("TX");
            
                    // "TX" is ?
                    TX tx = new TX(r01);
                    tx.setValue(data.text);
            
                    obx.getObservationValue(0).setData(tx);
            
                    Parser parser = hl7Context.getPipeParser();
    
                    String encodedMessage = parser.encode(r01);
                    listeners.fire(new DispatchLine(encodedMessage));
                    
                    
                    // Now, let's encode the message and look at the output
                    Connection hapiConnection = HL7Emitter.this.hl7Connection;
                    if(null != hapiConnection) {
    
            
                        Initiator initiator = hapiConnection.getInitiator();
                        Message response = initiator.sendAndReceive(r01);
                        String responseString = parser.encode(response);
                        log.debug("Received Response:"+responseString);
                        
                    }
                } catch (DataTypeException e) {
                    log.error("", e);
                } catch (HL7Exception e) {
                    log.error("", e);
                } catch (IOException e) {
                    log.error("", e);
                } catch (LLPException e) {
                    log.error("", e);
                } finally {
                    
                }
            }
        }        
    };
    
    final NumericInstanceModelListener numericListener = new NumericInstanceModelListener() {
        
        @Override
        public void instanceSample(InstanceModel<Numeric, NumericDataReader> model, NumericDataReader reader, Numeric data, SampleInfo sampleInfo) {
            if(rosetta.MDC_ECG_HEART_RATE.VALUE.equals(data.metric_id)) {
                if(Type.V26.equals(type)) {
                    try {
                        
                        
                        ORU_R01 r01 = new ORU_R01();
                        // ORU is an observation
                        // Event R01 is an unsolicited observation message
                        // "T" for Test, "P" for Production, etc.
                        r01.initQuickstart("ORU", "R01", "T");
                
                        // Populate the MSH Segment
                        MSH mshSegment = r01.getMSH();
                        mshSegment.getSendingApplication().getNamespaceID().setValue("ICE");
                        mshSegment.getSequenceNumber().setValue("123");
                
                        // Populate the PID Segment
                        ORU_R01_PATIENT patient = r01.getPATIENT_RESULT().getPATIENT();
                        PID pid = patient.getPID();
                        pid.getPatientName(0).getFamilyName().getSurname().setValue("Doe");
                        pid.getPatientName(0).getGivenName().setValue("John");
                        pid.getPatientIdentifierList(0).getIDNumber().setValue("123456");
                
                        ORU_R01_ORDER_OBSERVATION orderObservation = r01.getPATIENT_RESULT().getORDER_OBSERVATION();
                
                        orderObservation.getOBR().getObr7_ObservationDateTime().setValueToSecond(new Date());
                        
                        ORU_R01_OBSERVATION observation = orderObservation.getOBSERVATION(0);
                        
                        
                        // Populate the first OBX
                        OBX obx = observation.getOBX();
                        //obx.getSetIDOBX().setValue("1");
                        obx.getObservationIdentifier().getIdentifier().setValue("0002-4182");
                        obx.getObservationIdentifier().getText().setValue("HR");
                        obx.getObservationIdentifier().getCwe3_NameOfCodingSystem().setValue("MDIL");
                        obx.getObservationSubID().setValue("0");
                        obx.getUnits().getIdentifier().setValue("0004-0aa0");
                        obx.getUnits().getText().setValue("bpm");
                        obx.getUnits().getCwe3_NameOfCodingSystem().setValue("MDIL");
                        obx.getObservationResultStatus().setValue("F");
                
                        // The first OBX has a value type of CE. So first, we populate OBX-2 with "CE"...
                        obx.getValueType().setValue("NM");
                
                        // "NM" is Numeric
                        NM nm = new NM(r01);
                        nm.setValue(Float.toString(data.value));
                
                        obx.getObservationValue(0).setData(nm);
                
                        Parser parser = hl7Context.getPipeParser();
    
                        String encodedMessage = parser.encode(r01);
                        listeners.fire(new DispatchLine(encodedMessage));
                        
                        
                        // Now, let's encode the message and look at the output
                        Connection hapiConnection = HL7Emitter.this.hl7Connection;
                        if(null != hapiConnection) {
    
                
                            Initiator initiator = hapiConnection.getInitiator();
                            Message response = initiator.sendAndReceive(r01);
                            String responseString = parser.encode(response);
                            log.debug("Received Response:"+responseString);
                            
                        }
                    } catch (DataTypeException e) {
                        log.error("", e);
                    } catch (HL7Exception e) {
                        log.error("", e);
                    } catch (IOException e) {
                        log.error("", e);
                    } catch (LLPException e) {
                        log.error("", e);
                    } finally {
                        
                    }
                } else if(Type.FHIR_DSTU2.equals(type)) {
//                    Device device = new Device();
                    
                    Patient patient = new Patient();
                    patient.addIdentifier().setUse(OFFICIAL).setSystem("urn:fake:mrns").setValue("12345");
                    patient.addName().addFamily("Smith").addGiven("John").addGiven("Q");
                    patient.setGender(MALE);

                    
                    // TODO Needs to exist and be referred to
//                    Device device = new Device();
                    // TODO Probably needs to exist and be referred to
//                    DeviceMetric deviceMetric = new DeviceMetric();
                    Observation obs = new Observation();
                    obs.setValue(new QuantityDt(data.value).setUnits(data.unit_id).setCode(data.metric_id).setSystem("OpenICE"));
                    obs.addIdentifier().setSystem("urn:info.openice").setValue(uuidFromSequence(sampleInfo.publication_sequence_number).toString());
                    Date presentation = new Date(data.presentation_time.sec*1000L+data.presentation_time.nanosec/1000000L);
                    obs.setApplies(new DateTimeDt(presentation, TemporalPrecisionEnum.SECOND, TimeZone.getTimeZone("UTC")));
                    obs.setSubject(new ResourceReferenceDt(patient));
                    
                    
                    
                    IGenericClient client = fhirClient;
                    if(null != client) {
                        MethodOutcome outcome = client.update()
                        .resource(patient)
                        .conditional()
                        .where(Patient.IDENTIFIER.exactly().systemAndIdentifier("urn:fake:mrns", "12345"))
                        .execute();
                        
                        System.out.println(outcome);
                        outcome = client.create()
                        .resource(obs)
                        .execute();
                        System.out.println(outcome);
                    }
                    
                    
//                    String xmlEncoded = ctx.newXmlParser().encodeResourceToString(patient);
                    String jsonEncoded = fhirContext.newJsonParser().encodeResourceToString(obs);
                    
                    listeners.fire(new DispatchLine(jsonEncoded+"\n"));
                }
            } 
        } 
        
        @Override
        public void instanceNotAlive(InstanceModel<Numeric, NumericDataReader> model, NumericDataReader reader, Numeric keyHolder, SampleInfo sampleInfo) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void instanceAlive(InstanceModel<Numeric, NumericDataReader> model, NumericDataReader reader, Numeric data, SampleInfo sampleInfo) {
            // TODO Auto-generated method stub
            
        }
    };
    final AlarmSettingsInstanceModelListener alarmSettingsListener = new AlarmSettingsInstanceModelListener() {
        
        @Override
        public void instanceSample(InstanceModel<AlarmSettings, AlarmSettingsDataReader> model, AlarmSettingsDataReader reader, AlarmSettings data,
                SampleInfo sampleInfo) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void instanceNotAlive(InstanceModel<AlarmSettings, AlarmSettingsDataReader> model, AlarmSettingsDataReader reader,
                AlarmSettings keyHolder, SampleInfo sampleInfo) {
            // TODO Auto-generated method stub
            
        }
        
        @Override
        public void instanceAlive(InstanceModel<AlarmSettings, AlarmSettingsDataReader> model, AlarmSettingsDataReader reader, AlarmSettings data,
                SampleInfo sampleInfo) {
            // TODO Auto-generated method stub
            
        }
        

    };
    
    protected static UUID uuidFromSequence(SequenceNumber_t seq) {
        return new UUID(seq.high, seq.low);
    }
    
    public static void main(String[] args) {
        System.out.println(uuidFromSequence(new SequenceNumber_t(1, 1L)));
    }
}
