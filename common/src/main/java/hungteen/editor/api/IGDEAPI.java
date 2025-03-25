package hungteen.editor.api;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * HTLib 对外提供的 API 接口，不依赖于平台，只有一个有效实现。
 * @author PangTeen
 * @program HTLib
 * @data 2022/11/22 8:56
 */
public interface IGDEAPI {

    String MOD_ID = "htlib";

    Logger LOGGER = LogUtils.getLogger();

    IGDEAPI INSTANCE = ServiceHelper.findService(IGDEAPI.class, () -> new IGDEAPI() {});

    /**
     * Obtain the Mod API, either a valid implementation if mod is present, else
     * a dummy instance instead if mod is absent.
     */
    static IGDEAPI get() {
        return INSTANCE;
    }

    static String id() {
        return MOD_ID;
    }

    /**
     * @return the log instance for the mod.
     */
    static Logger logger() {
        return LOGGER;
    }

    /**
     * @return A unique version number for this version of the API.
     */
    default int apiVersion() {
        return 0;
    }

}
