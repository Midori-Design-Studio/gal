#include "gal_plugin.h"

#include <flutter/event_channel.h>
#include <flutter/event_sink.h>
#include <flutter/event_stream_handler_functions.h>
#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>
#include <windows.h>  // This must be included before many other Windows headers.
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.System.h>

#include <optional>

#define E_UNSUPPORTED_FORMAT ((HRESULT)0x80001000L)

using namespace winrt::Windows::Storage;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::System;
using namespace winrt::Windows::Storage::Streams;

namespace gal {

struct BytePattern {
  std::vector<uint8_t> pattern;
  int offset;
};

// https://support.microsoft.com/en-au/topic/graphic-file-types-c0374c44-71f8-45dc-a4d5-708064b5c99b
std::map<std::wstring, BytePattern> extension_map = {
    {L"jpg", {{0xFF, 0xD8}, 0}},
    {L"gif", {{0x47, 0x49, 0x46}, 0}},
    {L"bmp", {{0x42, 0x4D}, 0}},
    {L"png", {{0x89, 0x50, 0x4E, 0x47}, 0}},
    {L"tiff", {{0x49, 0x49, 0x2A, 0x00}, 0}},
    {L"tiff", {{0x4D, 0x4D, 0x00, 0x2A}, 0}},
    {L"emf", {{0x20, 0x45, 0x4D, 0x46}, 40}}};

std::wstring GetExtension(const std::vector<uint8_t>& data) {
  for (const auto& [ext, pattern] : extension_map) {
    if (data.size() < pattern.pattern.size() + pattern.offset) continue;
    if (std::equal(pattern.pattern.begin(), pattern.pattern.end(),
                   data.begin() + pattern.offset)) {
      return ext;
    }
  }
  throw winrt::hresult_error(E_UNSUPPORTED_FORMAT,
                             L"Image file format must be supported by Windows "
                             L"(.jpg, .png, .gif, .bmp, .tiff, .emf).");
}

void HandleError(
    const winrt::hresult_error& e,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  auto message = winrt::to_string(e.message());
  switch (e.code()) {
    case HRESULT_FROM_WIN32(ERROR_DISK_FULL):
      result->Error("NOT_ENOUGH_SPACE", message);
      break;
    case E_UNSUPPORTED_FORMAT:
      result->Error("NOT_SUPPORTED_FORMAT", message);
      break;
    default:
      result->Error("UNEXPECTED", message);
      break;
  }
}

IAsyncAction Open() { co_await Launcher::LaunchUriAsync(Uri(L"ms-photos:")); }

static IAsyncAction PutMedia(const std::string& path,
                             std::optional<std::string> album) {
  StorageFile file =
      co_await StorageFile::GetFileFromPathAsync(winrt::to_hstring(path));
  StorageFolder folder = KnownFolders::PicturesLibrary();
  if (album) {
    folder = co_await folder.CreateFolderAsync(
        winrt::to_hstring(album.value()),
        CreationCollisionOption::OpenIfExists);
  }
  co_await file.CopyAsync(folder, file.Name(),
                          NameCollisionOption::GenerateUniqueName);
}

static IAsyncAction PutMediaBytes(const std::vector<uint8_t>& bytes,
                                  std::optional<std::string> album) {
  StorageFolder folder = KnownFolders::PicturesLibrary();
  if (album) {
    folder = co_await folder.CreateFolderAsync(
        winrt::to_hstring(album.value()),
        CreationCollisionOption::OpenIfExists);
  }
  StorageFile file = co_await folder.CreateFileAsync(
      L"image." + GetExtension(bytes),
      CreationCollisionOption::GenerateUniqueName);

  auto stream = co_await file.OpenAsync(FileAccessMode::ReadWrite);
  DataWriter dataWriter(stream.GetOutputStreamAt(0));
  dataWriter.WriteBytes(bytes);
  co_await dataWriter.StoreAsync();
  co_await dataWriter.FlushAsync();
  dataWriter.DetachStream();
}

void GalPlugin::RegisterWithRegistrar(
    flutter::PluginRegistrarWindows* registrar) {
  auto channel =
      std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
          registrar->messenger(), "gal",
          &flutter::StandardMethodCodec::GetInstance());

  auto plugin = std::make_unique<GalPlugin>();

  channel->SetMethodCallHandler(
      [plugin_pointer = plugin.get()](const auto& call, auto result) {
        plugin_pointer->HandleMethodCall(call, std::move(result));
      });

  registrar->AddPlugin(std::move(plugin));
}

GalPlugin::GalPlugin() {}

GalPlugin::~GalPlugin() {}

void GalPlugin::HandleMethodCall(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  const auto* args =
      std::get_if<flutter::EncodableMap>(method_call.arguments());
  const std::string method = method_call.method_name();
  if (method == "putImage" || method == "putVideo") {
    std::optional<std::string> album;
    auto encodable_value = args->at(flutter::EncodableValue("album"));
    if (auto string_ptr = std::get_if<std::string>(&encodable_value)) {
      album = *string_ptr;
    }
    auto path =
        std::get<std::string>(args->at(flutter::EncodableValue("path")));
    std::replace(path.begin(), path.end(), '/', '\\');
    std::thread([path, album, result = std::move(result)]() mutable {
      try {
        PutMedia(path, album).get();
        result->Success();
      } catch (const winrt::hresult_error& e) {
        HandleError(e, std::move(result));
      }
    }).detach();
  } else if (method == "putImageBytes") {
    auto bytes = std::get<std::vector<uint8_t>>(
        args->at(flutter::EncodableValue("bytes")));
    std::optional<std::string> album;
    auto encodable_value = args->at(flutter::EncodableValue("album"));
    if (auto string_ptr = std::get_if<std::string>(&encodable_value)) {
      album = *string_ptr;
    }
    std::thread([bytes, album, result = std::move(result)]() mutable {
      try {
        PutMediaBytes(bytes, album).get();
        result->Success();
      } catch (const winrt::hresult_error& e) {
        HandleError(e, std::move(result));
      }
    }).detach();
  } else if (method == "open") {
    std::thread([] { Open(); }).detach();
    result->Success();
  } else if (method == "hasAccess" || method == "requestAccess") {
    result->Success(true);
  } else {
    result->NotImplemented();
  }
}

}  // namespace gal
