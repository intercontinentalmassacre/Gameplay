package com.winlator.core;

import android.content.Context;

import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import java.util.StringJoiner;

public class DXVKHelper {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("DXVK_STATE_CACHE_PATH", imageFs.getRootDir().getAbsolutePath() + ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0")) {
            envVars.put("DXVK_FEATURE_LEVEL", maxFeatureLevel);
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }
        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        envVars.put("DXVK_CONFIG_FILE", imageFs.getRootDir() + ImageFs.CONFIG_PATH + "/dxvk.conf");
        envVars.put("DXVK_CONFIG", buildInlineConfig(config));
    }

    static String buildInlineConfig(KeyValueSet config) {
        StringJoiner options = new StringJoiner(";");
        String memory = config.get("maxDeviceMemory");
        if (!memory.isEmpty() && !memory.equals("0")) {
            options.add("dxgi.maxDeviceMemory = " + memory);
            options.add("dxgi.maxSharedMemory = " + memory);
        }

        String featureLevel = config.get("maxFeatureLevel");
        if (!featureLevel.isEmpty() && !featureLevel.equals("0")) {
            options.add("d3d11.maxFeatureLevel = " + featureLevel);
        }

        String[] device = config.get("customDevice").split(":", 3);
        if (device.length == 3) {
            for (String api : new String[]{"dxgi", "d3d9"}) {
                options.add(api + ".customDeviceId = " + device[0]);
                options.add(api + ".customVendorId = " + device[1]);
                options.add(api + ".customDeviceDesc = \"" + device[2] + "\"");
            }
        }
        if (config.getBoolean("constantBufferRangeCheck")) {
            options.add("d3d11.constantBufferRangeCheck = \"True\"");
        }
        return options.toString();
    }

    public static void setVKD3DEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String featureLevel = config.get("vkd3dFeatureLevel", "12_1");
        envVars.put("VKD3D_FEATURE_LEVEL", featureLevel);
    }
}
