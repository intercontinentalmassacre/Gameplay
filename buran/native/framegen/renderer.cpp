#include <framegen/framegen.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <vulkan/vulkan_android.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {

void check(VkResult result, const char* call) {
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        __android_log_print(ANDROID_LOG_WARN, "BuranFramegen", "%s: %d", call, result);
        throw std::runtime_error(call);
    }
}
#define VK(call) check((call), #call)

constexpr VkFormat rgba = VK_FORMAT_R8G8B8A8_UNORM;
constexpr VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
constexpr VkImageSubresourceLayers layer{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
constexpr VkPipelineStageFlags transfer = VK_PIPELINE_STAGE_TRANSFER_BIT;
constexpr auto compute = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
constexpr auto top = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
constexpr auto bottom = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
constexpr uint32_t probeSide = 32;
constexpr size_t probeBytes = probeSide * probeSide * 4;
using Clock = std::chrono::steady_clock;

uint64_t hashPixels(const uint8_t* pixels, uint32_t width, uint32_t height, size_t stride) {
    uint64_t hash = 14695981039346656037ULL;
    for (uint32_t row = 0; row < height; ++row)
        for (uint32_t col = 0; col < width * 4; ++col)
            hash = (hash ^ pixels[size_t(row) * stride + col]) * 1099511628211ULL;
    return hash;
}

struct Picture {
    fg_image input{VK_NULL_HANDLE, VK_NULL_HANDLE, VK_IMAGE_LAYOUT_GENERAL, rgba};
    VkDeviceMemory memory{};
    VkImageLayout layout{VK_IMAGE_LAYOUT_UNDEFINED};
};
struct SurfaceImage {
    VkImage image{};
    VkSemaphore presented{};
};
struct Renderer {
    ANativeWindow* window{};
    uint32_t width{}, height{};
    VkExtent2D surfaceSize{};
    VkInstance instance{};
    VkSurfaceKHR surface{};
    VkPhysicalDevice physical{};
    VkDevice device{};
    VkQueue queue{};
    VkSwapchainKHR swapchain{};
    std::vector<SurfaceImage> surfaces;
    VkCommandPool pool{};
    VkCommandBuffer commands{};
    VkFence finished{};
    VkSemaphore acquired{};
    VkBuffer staging{};
    VkDeviceMemory stagingMemory{};
    uint8_t* pixels{};
    Picture picture[4]{};
    fg_context* generator{};
    unsigned frames{};
    unsigned next{};
    int64_t lastTimestamp{};
    Clock::time_point frameStart{};
    bool pending{};
    bool reportedGeneration{};
    unsigned diagnosticFrames{};
    bool havePreviousHash{};
    uint64_t previousHash{};

    VkDeviceSize imageBytes() const { return VkDeviceSize(width) * height * 4; }
    uint32_t probeWidth() const { return std::min(probeSide, width); }
    uint32_t probeHeight() const { return std::min(probeSide, height); }
    uint32_t probeX() const { return (width - probeWidth()) / 2; }
    uint32_t probeY() const { return (height - probeHeight()) / 2; }

    ~Renderer() {
        if (device) {
            vkDeviceWaitIdle(device);
            fg_destroy(generator);
            vkDestroyCommandPool(device, pool, nullptr);
            for (auto& entry : picture) {
                vkDestroyImageView(device, entry.input.view, nullptr);
                vkDestroyImage(device, entry.input.image, nullptr);
                vkFreeMemory(device, entry.memory, nullptr);
            }
            if (pixels) vkUnmapMemory(device, stagingMemory);
            vkDestroyBuffer(device, staging, nullptr);
            vkFreeMemory(device, stagingMemory, nullptr);
            for (const auto& entry : surfaces)
                vkDestroySemaphore(device, entry.presented, nullptr);
            vkDestroySemaphore(device, acquired, nullptr);
            vkDestroyFence(device, finished, nullptr);
            vkDestroySwapchainKHR(device, swapchain, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (surface) vkDestroySurfaceKHR(instance, surface, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
        if (window) ANativeWindow_release(window);
    }

    VkDeviceMemory allocate(VkMemoryRequirements req, VkMemoryPropertyFlags flags) {
        VkPhysicalDeviceMemoryProperties props{};
        vkGetPhysicalDeviceMemoryProperties(physical, &props);
        for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
            if (!(req.memoryTypeBits & (1u << i)) || (props.memoryTypes[i].propertyFlags & flags) != flags)
                continue;
            VkMemoryAllocateInfo info{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            info.allocationSize = req.size;
            info.memoryTypeIndex = i;
            VkDeviceMemory memory{};
            VK(vkAllocateMemory(device, &info, nullptr, &memory));
            return memory;
        }
        throw std::runtime_error("No compatible memory available for frame generation");
    }

    void init(const char* shaders) {
        const char* instanceExtensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Buran";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ii{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ii.pApplicationInfo = &app;
        ii.enabledExtensionCount = 2;
        ii.ppEnabledExtensionNames = instanceExtensions;
        VK(vkCreateInstance(&ii, nullptr, &instance));
        VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        si.window = window;
        VK(vkCreateAndroidSurfaceKHR(instance, &si, nullptr, &surface));

        uint32_t count{};
        VK(vkEnumeratePhysicalDevices(instance, &count, nullptr));
        std::vector<VkPhysicalDevice> devices(count);
        VK(vkEnumeratePhysicalDevices(instance, &count, devices.data()));
        uint32_t family{};
        for (const auto candidate : devices) {
            VkPhysicalDeviceFeatures features{};
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceFeatures(candidate, &features);
            vkGetPhysicalDeviceProperties(candidate, &properties);
            if (!features.shaderStorageImageExtendedFormats || properties.apiVersion < VK_API_VERSION_1_1)
                continue;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, nullptr);
            std::vector<VkQueueFamilyProperties> queues(count);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &count, queues.data());
            for (uint32_t i = 0; i < count; ++i) {
                VkBool32 present{};
                VK(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, &present));
                constexpr auto required = VK_QUEUE_COMPUTE_BIT | VK_QUEUE_GRAPHICS_BIT;
                if (present && (queues[i].queueFlags & required) == required) {
                    physical = candidate;
                    family = i;
                    break;
                }
            }
            if (physical) break;
        }
        if (!physical) throw std::runtime_error("No compatible Android Vulkan device");
        const float priority = 1.f;
        const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qi.queueFamilyIndex = family;
        qi.queueCount = 1;
        qi.pQueuePriorities = &priority;
        VkPhysicalDeviceFeatures features{};
        features.shaderStorageImageExtendedFormats = VK_TRUE;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        di.queueCreateInfoCount = 1;
        di.pQueueCreateInfos = &qi;
        di.pEnabledFeatures = &features;
        di.enabledExtensionCount = 1;
        di.ppEnabledExtensionNames = deviceExtensions;
        VK(vkCreateDevice(physical, &di, nullptr, &device));
        vkGetDeviceQueue(device, family, 0, &queue);
        initSwapchain();

        VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pi.queueFamilyIndex = family;
        VK(vkCreateCommandPool(device, &pi, nullptr, &pool));
        VkCommandBufferAllocateInfo ci{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ci.commandPool = pool;
        ci.commandBufferCount = 1;
        VK(vkAllocateCommandBuffers(device, &ci, &commands));
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        VK(vkCreateFence(device, &fi, nullptr, &finished));
        VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VK(vkCreateSemaphore(device, &semaphore, nullptr, &acquired));

        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size = imageBytes() + probeBytes;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        VK(vkCreateBuffer(device, &bi, nullptr, &staging));
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, staging, &req);
        stagingMemory = allocate(req, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        VK(vkBindBufferMemory(device, staging, stagingMemory, 0));
        void* mapping{};
        VK(vkMapMemory(device, stagingMemory, 0, bi.size, 0, &mapping));
        pixels = static_cast<uint8_t*>(mapping);

        for (auto& entry : picture) {
            VkImageCreateInfo im{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            im.imageType = VK_IMAGE_TYPE_2D;
            im.format = rgba;
            im.extent = {width, height, 1};
            im.arrayLayers = im.mipLevels = 1;
            im.samples = VK_SAMPLE_COUNT_1_BIT;
            im.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                       VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            VK(vkCreateImage(device, &im, nullptr, &entry.input.image));
            vkGetImageMemoryRequirements(device, entry.input.image, &req);
            entry.memory = allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            VK(vkBindImageMemory(device, entry.input.image, entry.memory, 0));
            VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            view.image = entry.input.image;
            view.viewType = VK_IMAGE_VIEW_TYPE_2D;
            view.format = rgba;
            view.subresourceRange = range;
            VK(vkCreateImageView(device, &view, nullptr, &entry.input.view));
        }

        fg_desc desc{};
        desc.instance = instance;
        desc.physical_device = physical;
        desc.device = device;
        desc.queue = queue;
        desc.queue_family = family;
        desc.command_pool = pool;
        desc.color_format = rgba;
        desc.width = width;
        desc.height = height;
        desc.flow_scale = .25f;
        desc.quality = 2;
        desc.shader_dir = shaders;
        if (fg_create(&desc, &generator) != FG_OK)
            throw std::runtime_error("Framegen Free could not initialize");
    }

    void initSwapchain() {
        VkSurfaceCapabilitiesKHR cap{};
        VK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &cap));
        if (!(cap.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT))
            throw std::runtime_error("Android surface cannot accept Vulkan transfer");
        surfaceSize = cap.currentExtent;
        if (surfaceSize.width == UINT32_MAX) {
            surfaceSize.width = std::clamp(width, cap.minImageExtent.width, cap.maxImageExtent.width);
            surfaceSize.height = std::clamp(height, cap.minImageExtent.height, cap.maxImageExtent.height);
        }

        uint32_t count{};
        VK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, nullptr));
        std::vector<VkSurfaceFormatKHR> formats(count);
        VK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, formats.data()));
        VkFormatProperties sourceFeatures{};
        vkGetPhysicalDeviceFormatProperties(physical, rgba, &sourceFeatures);
        VkSurfaceFormatKHR pick{VK_FORMAT_UNDEFINED, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
        for (auto format : formats) {
            if (format.format == VK_FORMAT_UNDEFINED) format.format = rgba;
            if (format.colorSpace != VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) continue;
            if (format.format != rgba && format.format != VK_FORMAT_B8G8R8A8_UNORM) continue;
            VkFormatProperties targetFeatures{};
            vkGetPhysicalDeviceFormatProperties(physical, format.format, &targetFeatures);
            if ((sourceFeatures.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT) &&
                (targetFeatures.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
                pick = format;
                if (format.format == rgba) break;
            }
        }
        if (pick.format == VK_FORMAT_UNDEFINED || !surfaceSize.width || !surfaceSize.height)
            throw std::runtime_error("Android surface offers no compatible 8-bit blit format");

        VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        info.surface = surface;
        info.minImageCount = std::max(cap.minImageCount, 3u);
        if (cap.maxImageCount) info.minImageCount = std::min(info.minImageCount, cap.maxImageCount);
        info.imageFormat = pick.format;
        info.imageColorSpace = pick.colorSpace;
        info.imageExtent = surfaceSize;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.preTransform = (cap.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : cap.currentTransform;
        for (auto alpha : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                           VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
            if (cap.supportedCompositeAlpha & alpha) { info.compositeAlpha = alpha; break; }
        }
        info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        info.clipped = VK_TRUE;
        VK(vkCreateSwapchainKHR(device, &info, nullptr, &swapchain));
        VK(vkGetSwapchainImagesKHR(device, swapchain, &count, nullptr));
        std::vector<VkImage> images(count);
        VK(vkGetSwapchainImagesKHR(device, swapchain, &count, images.data()));
        surfaces.resize(count);
        VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        for (uint32_t i = 0; i < count; ++i) {
            surfaces[i].image = images[i];
            VK(vkCreateSemaphore(device, &semaphore, nullptr, &surfaces[i].presented));
        }
    }

    void barrier(VkImage image, VkImageLayout before, VkImageLayout after,
                 VkPipelineStageFlags src, VkAccessFlags read, VkPipelineStageFlags dst, VkAccessFlags write) {
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.srcAccessMask = read;
        b.dstAccessMask = write;
        b.oldLayout = before;
        b.newLayout = after;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = image;
        b.subresourceRange = range;
        vkCmdPipelineBarrier(commands, src, dst, 0, 0, nullptr, 0, nullptr, 1, &b);
    }

    void move(Picture& pic, VkImageLayout layout, VkPipelineStageFlags dst, VkAccessFlags access) {
        auto src = pic.layout == VK_IMAGE_LAYOUT_UNDEFINED ? top :
            VkPipelineStageFlags(transfer | compute);
        auto before = pic.layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0u :
            VkAccessFlags(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT |
                          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        barrier(pic.input.image, pic.layout, layout, src, before, dst, access);
        pic.layout = layout;
    }

    void wait() {
        if (!pending) return;
        VK(vkWaitForFences(device, 1, &finished, VK_TRUE, 3000000000ULL));
        pending = false;
    }

    void begin() {
        wait();
        VK(vkResetCommandBuffer(commands, 0));
        VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        VK(vkBeginCommandBuffer(commands, &info));
    }

    void show(Picture& pic, bool sample = false) {
        uint32_t index{};
        VK(vkAcquireNextImageKHR(device, swapchain, 500000000ULL, acquired, VK_NULL_HANDLE, &index));
        const auto& out = surfaces[index];
        move(pic, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, transfer, VK_ACCESS_TRANSFER_READ_BIT);
        barrier(out.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                top, 0, transfer, VK_ACCESS_TRANSFER_WRITE_BIT);
        VkImageBlit blit{};
        blit.srcSubresource = blit.dstSubresource = layer;
        blit.srcOffsets[1] = {int32_t(width), int32_t(height), 1};
        blit.dstOffsets[1] = {int32_t(surfaceSize.width), int32_t(surfaceSize.height), 1};
        vkCmdBlitImage(commands, pic.input.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       out.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
        if (sample) {
            VkBufferImageCopy readback{};
            readback.bufferOffset = imageBytes();
            readback.imageSubresource = layer;
            readback.imageOffset = {int32_t(probeX()), int32_t(probeY()), 0};
            readback.imageExtent = {probeWidth(), probeHeight(), 1};
            vkCmdCopyImageToBuffer(commands, pic.input.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                   staging, 1, &readback);
            VkBufferMemoryBarrier hostRead{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
            hostRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            hostRead.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            hostRead.srcQueueFamilyIndex = hostRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostRead.buffer = staging;
            hostRead.offset = imageBytes();
            hostRead.size = probeBytes;
            vkCmdPipelineBarrier(commands, transfer, VK_PIPELINE_STAGE_HOST_BIT, 0,
                                 0, nullptr, 1, &hostRead, 0, nullptr);
        }
        move(pic, VK_IMAGE_LAYOUT_GENERAL, compute, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        barrier(out.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                transfer, VK_ACCESS_TRANSFER_WRITE_BIT, bottom, 0);
        VK(vkEndCommandBuffer(commands));
        VK(vkResetFences(device, 1, &finished));
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &acquired;
        submit.pWaitDstStageMask = &transfer;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commands;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &out.presented;
        VK(vkQueueSubmit(queue, 1, &submit, finished));
        pending = true;
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &out.presented;
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain;
        present.pImageIndices = &index;
        VK(vkQueuePresentKHR(queue, &present));
    }

    bool stage(const uint8_t* data, int rowStride, int pixelStride) {
        const size_t row = size_t(width) * 4;
        if (frames && pixelStride == 4) {
            uint32_t y = 0;
            while (y < height && std::memcmp(data + size_t(y) * rowStride, pixels + size_t(y) * row, row) == 0) ++y;
            if (y == height) return false;
        }
        frameStart = Clock::now();
        wait();
        for (uint32_t y = 0; y < height; ++y) {
            const auto* source = data + size_t(y) * rowStride;
            auto* target = pixels + size_t(y) * row;
            if (pixelStride == 4) {
                memcpy(target, source, row);
            } else {
                for (uint32_t x = 0; x < width; ++x)
                    memcpy(target + size_t(x) * 4, source + size_t(x) * pixelStride, 4);
            }
        }
        return true;
    }

    bool present(int64_t timestamp) {
        const size_t row = size_t(width) * 4;
        begin();
        const uint64_t inputHash = diagnosticFrames
            ? hashPixels(pixels + size_t(probeY()) * row + size_t(probeX()) * 4, probeWidth(), probeHeight(), row) : 0;
        Picture& current = picture[next];
        move(current, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, transfer, VK_ACCESS_TRANSFER_WRITE_BIT);
        VkBufferImageCopy region{};
        region.imageSubresource = layer;
        region.imageExtent = {width, height, 1};
        vkCmdCopyBufferToImage(commands, staging, current.input.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        move(current, VK_IMAGE_LAYOUT_GENERAL, compute, VK_ACCESS_SHADER_READ_BIT);
        const auto period = timestamp > lastTimestamp ? timestamp - lastTimestamp : 0;
        const bool interpolate = frames && period >= 9000000 && period <= 100000000;
        if (interpolate) {
            const fg_frame previous{picture[(next + 2) % 3].input, {}, {}};
            const fg_frame older{picture[(next + 1) % 3].input, {}, {}};
            const fg_frame latest{current.input, {}, {}};
            auto& generated = picture[3];
            move(generated, VK_IMAGE_LAYOUT_GENERAL, compute, VK_ACCESS_SHADER_WRITE_BIT);
            const float middle = .5f;
            if (fg_record_batch(generator, commands, frames > 1 ? &older : nullptr,
                                &previous, &latest, 1, &middle, &generated.input) != FG_OK)
                throw std::runtime_error(fg_last_error(generator));
            const bool probe = diagnosticFrames && havePreviousHash;
            show(generated, probe);
            wait();
            if (probe) {
                const auto result = hashPixels(pixels + imageBytes(), probeWidth(), probeHeight(), probeWidth() * 4);
                __android_log_print(ANDROID_LOG_INFO, "BuranFramegen",
                    "VKFG_PROBE t=0.5 previous=%016llx current=%016llx generated=%016llx present=ok",
                    (unsigned long long)previousHash, (unsigned long long)inputHash, (unsigned long long)result);
                --diagnosticFrames;
            }
            if (!reportedGeneration) {
                __android_log_print(ANDROID_LOG_INFO, "BuranFramegen",
                    "Presented an interpolated frame from Vulkan optical flow (%ux%u)", width, height);
                reportedGeneration = true;
            }
            VK(vkResetCommandBuffer(commands, 0));
            fg_recycle(generator);
            std::this_thread::sleep_until(frameStart + std::chrono::nanoseconds(period / 2));
            begin();
        }
        show(current);
        if (diagnosticFrames) {
            previousHash = inputHash;
            havePreviousHash = true;
        }
        lastTimestamp = timestamp;
        next = (next + 1) % 3;
        frames = std::min(frames + 1, 3u);
        return interpolate;
    }
};

void diagnostic(const std::exception& error) {
    __android_log_print(ANDROID_LOG_WARN, "BuranFramegen", "%s", error.what());
}
Renderer* renderer(jlong handle) {
    return reinterpret_cast<Renderer*>(static_cast<uintptr_t>(handle));
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_buran_runtime_display_FramegenNative_open(
    JNIEnv* env, jobject, jobject surface, jstring shaders, jint width, jint height) {
    if (!surface || !shaders || width < 16 || height < 16 || width > 4096 || height > 4096) return 0;
    auto state = std::make_unique<Renderer>();
    state->window = ANativeWindow_fromSurface(env, surface);
    if (!state->window) return 0;
    state->width = static_cast<uint32_t>(width);
    state->height = static_cast<uint32_t>(height);
    const char* directory = env->GetStringUTFChars(shaders, nullptr);
    if (!directory) return 0;
    try {
        state->init(directory);
    } catch (const std::exception& error) {
        diagnostic(error);
        env->ReleaseStringUTFChars(shaders, directory);
        return 0;
    }
    env->ReleaseStringUTFChars(shaders, directory);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(state.release()));
}

extern "C" JNIEXPORT jint JNICALL
Java_org_buran_runtime_display_FramegenNative_stage(
    JNIEnv* env, jobject, jlong handle, jobject buffer, jint rowStride, jint pixelStride) {
    const auto state = renderer(handle);
    if (!state || !buffer || rowStride <= 0 || pixelStride < 4) return -1;
    const auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const int64_t row = int64_t(state->width - 1) * pixelStride + 4;
    const int64_t size = int64_t(state->height - 1) * rowStride + row;
    if (!data || row > rowStride || env->GetDirectBufferCapacity(buffer) < size) return -1;
    try {
        return state->stage(data, rowStride, pixelStride) ? 1 : 0;
    } catch (const std::exception& error) {
        diagnostic(error);
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_buran_runtime_display_FramegenNative_present(JNIEnv*, jobject, jlong handle, jlong timestampNanos) {
    const auto state = renderer(handle);
    if (!state) return -1;
    try {
        return state->present(timestampNanos) ? 1 : 0;
    } catch (const std::exception& error) {
        diagnostic(error);
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_buran_runtime_display_FramegenNative_enableDiagnostics(JNIEnv*, jobject, jlong handle) {
    if (auto state = renderer(handle)) {
        state->diagnosticFrames = 8;
        state->havePreviousHash = false;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_buran_runtime_display_FramegenNative_close(JNIEnv*, jobject, jlong handle) {
    delete renderer(handle);
}
