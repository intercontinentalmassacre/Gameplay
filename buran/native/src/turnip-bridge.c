#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

/* DroidDeck's Android package already includes this AdrenoTools implementation. */
typedef void *(*open_adreno_loader)(int, int, const char *, const char *,
                                   const char *, const char *, const char *, void **);
enum { ADRENOTOOLS_DRIVER_CUSTOM = 1 };
static pthread_once_t loader_once = PTHREAD_ONCE_INIT;
static PFN_vkGetInstanceProcAddr get_instance_proc;
static PFN_vkGetDeviceProcAddr get_device_proc;

static int configure_headless_wine(void)
{
    typedef void (*set_wine_driver)(const void *, unsigned int);
    /* The bundled Max ARM64 win32u driver table uses Wine GDI ABI 108, with 1216 bytes.
       Wine fills omitted callbacks with its null display driver's implementations. */
    uint64_t callbacks[152] = {0};
    void *win32u = dlopen("win32u.so", RTLD_NOW | RTLD_NOLOAD);
    set_wine_driver set_driver = win32u ? (set_wine_driver)dlsym(win32u, "__wine_set_user_driver") : NULL;
    if (!set_driver) return 0;
    set_driver(callbacks, 108);
    dlclose(win32u);
    return 1;
}

static void init_loader(void)
{
    const char *driver_dir = getenv("BURAN_TURNIP_DRIVER_DIR");
    const char *driver_name = getenv("BURAN_TURNIP_DRIVER_NAME");
    const char *native_dir = getenv("BURAN_WINE_NATIVE_DIR");
    const char *headless_setting = getenv("BURAN_TURNIP_HEADLESS");
    if (headless_setting && !strcmp(headless_setting, "1") && !configure_headless_wine()) {
        __android_log_print(ANDROID_LOG_ERROR, "BuranTurnip", "Unable to initialize Wine's null display driver");
        return;
    }
    if (!driver_dir || !*driver_dir || !driver_name || !*driver_name || !native_dir || !*native_dir) {
        __android_log_print(ANDROID_LOG_ERROR, "BuranTurnip", "Turnip runtime paths are missing");
        return;
    }

    /* Bind the platform's namespace functions before AdrenoTools introduces its same-named data symbols. */
    if (!dlopen("libvndksupport.so", RTLD_LOCAL | RTLD_NOW)) {
        __android_log_print(ANDROID_LOG_ERROR, "BuranTurnip", "Vulkan platform support unavailable: %s", dlerror());
        return;
    }
    void *adreno = dlopen("libdroiddeckwayland.so", RTLD_LOCAL | RTLD_NOW);
    open_adreno_loader open_loader = adreno
        ? (open_adreno_loader)dlsym(adreno, "adrenotools_open_libvulkan") : NULL;
    if (!open_loader) {
        __android_log_print(ANDROID_LOG_ERROR, "BuranTurnip", "AdrenoTools unavailable: %s", dlerror());
        return;
    }

    void *loader = open_loader(RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, NULL,
                               native_dir, driver_dir, driver_name, NULL, NULL);
    if (!loader) {
        __android_log_print(ANDROID_LOG_ERROR, "BuranTurnip", "Unable to open the Android Vulkan loader with Turnip");
        return;
    }

    PFN_vkGetInstanceProcAddr instance =
        (PFN_vkGetInstanceProcAddr)dlsym(loader, "vkGetInstanceProcAddr");
    PFN_vkGetDeviceProcAddr device =
        (PFN_vkGetDeviceProcAddr)dlsym(loader, "vkGetDeviceProcAddr");
    if (!instance || !device) {
        __android_log_print(ANDROID_LOG_ERROR, "BuranTurnip", "Android Vulkan loader is missing a dispatch entry point");
        return;
    }
    get_instance_proc = instance;
    get_device_proc = device;
}

static VKAPI_ATTR VkResult VKAPI_CALL unavailable_create_instance(const VkInstanceCreateInfo *info,
    const VkAllocationCallbacks *allocator, VkInstance *instance)
{
    (void)info;
    (void)allocator;
    (void)instance;
    return VK_ERROR_INITIALIZATION_FAILED;
}

static VKAPI_ATTR VkResult VKAPI_CALL unavailable_enumerate_extensions(const char *layer,
    uint32_t *count, VkExtensionProperties *properties)
{
    (void)layer;
    (void)count;
    (void)properties;
    return VK_ERROR_INITIALIZATION_FAILED;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name)
{
    pthread_once(&loader_once, init_loader);
    if (get_instance_proc) return get_instance_proc(instance, name);
    if (name && !strcmp(name, "vkCreateInstance")) return (PFN_vkVoidFunction)unavailable_create_instance;
    if (name && !strcmp(name, "vkEnumerateInstanceExtensionProperties"))
        return (PFN_vkVoidFunction)unavailable_enumerate_extensions;
    return NULL;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *name)
{
    pthread_once(&loader_once, init_loader);
    return get_device_proc ? get_device_proc(device, name) : NULL;
}
