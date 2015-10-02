package com.waqas.hybrid.ar;

import com.waqas.hybrid.filters.NoneFilter;

public class NoneARFilter extends NoneFilter implements ARFilter {
    @Override
    public float[] getGLPose() {
        return null;
    }
}
