package org.mdpnp.apps.testapp.pca;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.mdpnp.apps.testapp.vital.BoundedRangeMultiModel;
import org.mdpnp.apps.testapp.vital.Value;
import org.mdpnp.apps.testapp.vital.Vital;
import org.mdpnp.apps.testapp.vital.VitalModel;
import org.mdpnp.apps.testapp.vital.VitalModelListener;

public class VitalValueMsBoundedRangeMulti implements BoundedRangeMultiModel {
        private final Vital vital;
        private final long minimum, maximum;
        
        public VitalValueMsBoundedRangeMulti(Vital vital) {
            this(vital, -50000L, 50000L);
            
        }

        public VitalValueMsBoundedRangeMulti(final Vital vital, long minimum, long maximum) {
            this.vital = vital;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        @Override
        public int getMinimum() {
            return (int) (minimum / 1000L);
        }

        @Override
        public void setMinimum(int newMinimum) {
        }

        @Override
        public int getMaximum() {
            return (int) (maximum / 1000L);
        }

        @Override
        public void setMaximum(int newMaximum) {
        }

        @Override
        public Float getValue(int idx) {
            switch (idx) {
            case 0:
                return null == vital.getValueMsWarningLow() ? null : (float) -vital.getValueMsWarningLow()/1000L;
            case 1:
                return null == vital.getValueMsWarningHigh() ? null : (float) vital.getValueMsWarningHigh()/1000L;
            default:
                throw new IllegalArgumentException("No such idx=" + idx);
            }
        }

        @Override
        public void setValue(int idx, Float newValue) {
            switch (idx) {
            case 0:
                vital.setValueMsWarningLow(null == newValue ? null : (-1000L * (long)(float)newValue));
                break;
            case 1:
                vital.setValueMsWarningHigh(null == newValue ? null : (1000L * (long)(float)newValue));
                break;
            default:
                throw new IllegalArgumentException("No such idx=" + idx);
            }
        }

        @Override
        public int getValueCount() {
            return 2;
        }

        private boolean valueIsAdjusting = false;

        @Override
        public void setValueIsAdjusting(boolean b) {
            valueIsAdjusting = b;
        }

        @Override
        public boolean getValueIsAdjusting() {
            return valueIsAdjusting;
        }

        private final class ChangeVitalAdapter implements VitalModelListener {
            private final ChangeListener listener;
            private final Vital vital;

            public ChangeVitalAdapter(ChangeListener listener, Vital vital) {
                this.listener = listener;
                this.vital = vital;
            }

            @Override
            public boolean equals(Object obj) {
                return listener.equals(obj);
            }

            @Override
            public int hashCode() {
                return listener.hashCode();
            }

            @Override
            public void vitalChanged(VitalModel model, Vital vital) {
                if (this.vital.equals(vital)) {
                    listener.stateChanged(CHANGE_EVENT);
                }
            }

            @Override
            public void vitalRemoved(VitalModel model, Vital vital) {
            }

            @Override
            public void vitalAdded(VitalModel model, Vital vital) {
            }
        }

        protected final ChangeEvent CHANGE_EVENT = new ChangeEvent(this);

        @Override
        public void addChangeListener(ChangeListener x) {
            vital.getParent().addListener(new ChangeVitalAdapter(x, vital));
        }

        @Override
        public void removeChangeListener(ChangeListener x) {
            vital.getParent().removeListener(new ChangeVitalAdapter(x, vital));
        }

        @Override
        public int getMarkerCount() {
            return vital.getValues().size();
        }
        
        @Override
        public Float getMarker(int idx) {
            Value val = vital.getValues().get(idx);
            if(val.getValueMsAboveHigh() > 0L) {
                return (float) val.getValueMsAboveHigh()/1000L;
            } else if(val.getValueMsBelowLow() > 0L) {
                return (float) -val.getValueMsBelowLow()/1000L;
            } else {
                return null;
            }
        }
}