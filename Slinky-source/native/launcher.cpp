// Slinky launcher — animated console UI.
// Cracked by xMqrO | discord.gg/hitsconfigs
#include <windows.h>
#include <tlhelp32.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <string>
#include <vector>

#define COL_RESET  "\x1b[0m"
#define COL_ORANGE "\x1b[38;2;255;165;0m"
#define COL_WHITE  "\x1b[97m"
#define COL_GRAY   "\x1b[90m"
#define COL_RED    "\x1b[91m"
#define COL_GREEN  "\x1b[92m"

// ── animation frames (6 lines each, no trailing newline) ────────────────────
// Exact frames extracted from the original slinky .exe binary
static const char* FRAMES[4] = {
  "  _                            \n"
  " ((                 ____      \n"
  " ||________________( ( e\\____o\n"
  " (                     _____/ \n"
  "  \\___________________/       \n"
  "   )  |           |   )       ",

  "  _                            \n"
  " ((                 ____      \n"
  " ||________________( ( e\\____o\n"
  " (                     _____/ \n"
  "  \\___________________/       \n"
  "  |   )           )   |",

  "  _                            \n"
  " ((                 ____      \n"
  " ||________________( ( e\\____o\n"
  " (                     _____/ \n"
  "  \\___________________/       \n"
  "   )  |           |   )       ",

  "  _                            \n"
  " ((                 ____      \n"
  " ||________________( ( e\\____o\n"
  " (                     _____/ \n"
  "  \\___________________/       \n"
  "  |   )           )   |",
};
// Total printed lines per render: 1 blank + 6 art + 1 blank + 1 credit + 1 blank = 10
// Status line is line 11, printed with \r (no \n) so cursor stays on it.
#define ANIM_LINES_ABOVE 10

// ── shared state written by main, read by anim thread ───────────────────────
static CRITICAL_SECTION g_cs;
static char   g_statusTag[48]  = "LOADING";
static char   g_statusMsg[512] = "Starting...";
static const char* g_statusCol = COL_GRAY;
static bool   g_statusFinal    = false; // when true, anim thread stops after one last draw
static std::atomic<bool> g_animRunning{false};

static void renderFrame(int f) {
  printf(COL_WHITE "\n%s\n" COL_RESET, FRAMES[f]);
  printf(COL_GRAY "  Slinky Cracked by " COL_ORANGE "xMqrO"
         COL_GRAY "     discord.gg/hitsconfigs\n\n" COL_RESET);
  EnterCriticalSection(&g_cs);
  printf("\r\x1b[K%s> %s" COL_RESET " %s%s" COL_RESET,
    g_statusCol, g_statusTag, COL_WHITE, g_statusMsg);
  LeaveCriticalSection(&g_cs);
  fflush(stdout);
}

static DWORD WINAPI animThreadProc(LPVOID) {
  int f = 0;
  bool first = true;
  while (g_animRunning) {
    if (!first) printf("\r\x1b[%dA", ANIM_LINES_ABOVE);
    first = false;
    renderFrame(f);
    f = (f + 1) % 4;
    Sleep(160);
  }
  // one final draw with the last status
  if (!first) printf("\r\x1b[%dA", ANIM_LINES_ABOVE);
  renderFrame(f);
  printf("\n");
  fflush(stdout);
  return 0;
}

static void startAnim() {
  g_animRunning = true;
  HANDLE t = CreateThread(nullptr, 0, animThreadProc, nullptr, 0, nullptr);
  if (t) CloseHandle(t);
}

static void stopAnim() {
  g_animRunning = false;
  Sleep(200); // let thread finish its last frame
}

static void setStatus(const char* tag, const char* msg,
                      const char* col = COL_ORANGE, bool final_ = false) {
  EnterCriticalSection(&g_cs);
  strncpy_s(g_statusTag, tag, _TRUNCATE);
  strncpy_s(g_statusMsg, msg, _TRUNCATE);
  g_statusCol = col;
  g_statusFinal = final_;
  LeaveCriticalSection(&g_cs);
  if (final_) { stopAnim(); }
}

// ── win32 helpers ────────────────────────────────────────────────────────────
namespace {
struct Candidate { DWORD pid{}; wchar_t executable[260]{}; wchar_t title[256]{}; };
std::wstring payloadPath;
std::vector<Candidate> candidates;
volatile LONG cancelled{};
std::wstring nativeLogPath;

struct Handle {
  HANDLE value{INVALID_HANDLE_VALUE};
  explicit Handle(HANDLE h) : value(h) {}
  ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
  explicit operator bool() const { return value && value != INVALID_HANDLE_VALUE; }
};

BOOL WINAPI consoleControl(DWORD e) {
  if (e == CTRL_C_EVENT || e == CTRL_BREAK_EVENT) { InterlockedExchange(&cancelled, 1); return TRUE; }
  return FALSE;
}

void setupConsole() {
  AllocConsole();
  FILE* s{};
  freopen_s(&s, "CONOUT$", "w", stdout);
  freopen_s(&s, "CONOUT$", "w", stderr);
  freopen_s(&s, "CONIN$",  "r", stdin);
  setvbuf(stdout, nullptr, _IONBF, 0);
  SetConsoleOutputCP(CP_UTF8);
  SetConsoleTitleW(L"Slinky");
  HANDLE h = GetStdHandle(STD_OUTPUT_HANDLE);
  DWORD mode = 0;
  if (GetConsoleMode(h, &mode))
    SetConsoleMode(h, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
  SetConsoleCtrlHandler(consoleControl, TRUE);
}

void windowsError(const wchar_t* op, DWORD error = GetLastError()) {
  wchar_t* detail{};
  FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
    FORMAT_MESSAGE_IGNORE_INSERTS, nullptr, error, 0,
    reinterpret_cast<wchar_t*>(&detail), 0, nullptr);
  int sz = WideCharToMultiByte(CP_UTF8, 0, detail ? detail : L"unknown", -1, nullptr, 0, nullptr, nullptr);
  std::string utf8(sz, '\0');
  WideCharToMultiByte(CP_UTF8, 0, detail ? detail : L"unknown", -1, utf8.data(), sz, nullptr, nullptr);
  if (detail) LocalFree(detail);
  char buf[600];
  snprintf(buf, sizeof(buf), "%ls - %s", op, utf8.c_str());
  setStatus("ERROR", buf, COL_RED, true);
}

enum class StartupStatus { Waiting, Running, AlreadyRunning, Failed, Reloaded };
StartupStatus lastStatus{StartupStatus::Waiting};

StartupStatus statusFromLine(const std::string& line) {
  if (line.find("FINAL STATUS: ALREADY_RUNNING") != std::string::npos ||
      line.find("already running in this process; ignoring second injection") != std::string::npos)
    return StartupStatus::AlreadyRunning;
  if (line.find("FINAL STATUS: FAILED") != std::string::npos ||
      line.find("bootstrap failed; unloading") != std::string::npos ||
      line.find("Bootstrap.start:") != std::string::npos) return StartupStatus::Failed;
  if (line.find("FINAL STATUS: RELOADED") != std::string::npos) return StartupStatus::Reloaded;
  if (line.find("FINAL STATUS: RUNNING") != std::string::npos ||
      line.find("Slinky is running") != std::string::npos) return StartupStatus::Running;
  return StartupStatus::Waiting;
}

struct LogReader {
  ULONGLONG offset{}; std::string pending;
  StartupStatus status{StartupStatus::Waiting}; bool finalStatus{}; DWORD error{};
  void consumeStatusLine(const std::string& line) {
    const auto next = statusFromLine(line);
    if (next == StartupStatus::Waiting) return;
    if (line.find("FINAL STATUS: ") != std::string::npos) { status = next; finalStatus = true; return; }
    if (finalStatus) return;
    if (next == StartupStatus::Failed) status = next;
    else if (next == StartupStatus::AlreadyRunning && status != StartupStatus::Failed) status = next;
    else if (status == StartupStatus::Waiting || status == StartupStatus::Running) status = next;
  }
  bool read(const std::wstring& path) {
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
      nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) { error = GetLastError(); return false; }
    LARGE_INTEGER size{}, position{};
    if (!GetFileSizeEx(file, &size)) { error = GetLastError(); CloseHandle(file); return false; }
    if (static_cast<ULONGLONG>(size.QuadPart) < offset) {
      offset = 0; pending.clear(); status = StartupStatus::Waiting; finalStatus = false;
    }
    position.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(file, position, nullptr, FILE_BEGIN)) { error = GetLastError(); CloseHandle(file); return false; }
    char buffer[4096]; DWORD count{}; BOOL readOk = TRUE;
    while ((readOk = ReadFile(file, buffer, sizeof buffer, &count, nullptr)) != FALSE && count) {
      offset += count; pending.append(buffer, count);
      size_t newline{};
      while ((newline = pending.find('\n')) != std::string::npos) {
        std::string line = pending.substr(0, newline + 1); pending.erase(0, newline + 1);
        if (line.compare(0, 3, "\xef\xbb\xbf") == 0) line.erase(0, 3);
        if (line.size() >= 2 && line[line.size()-2] == '\r') line.erase(line.size()-2, 1);
        consumeStatusLine(line);
      }
    }
    error = readOk ? ERROR_SUCCESS : GetLastError();
    CloseHandle(file); return readOk != FALSE;
  }
  void flushLastLine() {
    if (pending.empty()) return;
    if (pending.compare(0, 3, "\xef\xbb\xbf") == 0) pending.erase(0, 3);
    consumeStatusLine(pending); pending.clear();
  }
};

bool waitForStartup(DWORD pid, ULONGLONG started) {
  lastStatus = StartupStatus::Waiting;
  LogReader log;
  Handle process(OpenProcess(SYNCHRONIZE, FALSE, pid));
  ULONGLONG nextProgress = GetTickCount64() + 15000;
  ULONGLONG legacyAt{};
  while (!cancelled && GetTickCount64() - started < 250000) {
    log.read(nativeLogPath);
    if (log.status != StartupStatus::Waiting && !legacyAt) legacyAt = GetTickCount64();
    if (log.status != StartupStatus::Waiting && (log.finalStatus || GetTickCount64() - legacyAt >= 500)) {
      lastStatus = log.status;
      switch (log.status) {
        case StartupStatus::Running:
          setStatus("READY", "Press Right Shift in Minecraft.", COL_GREEN, true); break;
        case StartupStatus::Reloaded:
          setStatus("RELOADED", "Slinky reloaded. Press Right Shift.", COL_GREEN, true); break;
        case StartupStatus::AlreadyRunning:
          setStatus("SKIPPED", "Already running. Unload from menu first.", COL_ORANGE, true); break;
        case StartupStatus::Failed:
          setStatus("FAILED", "Slinky could not start.", COL_RED, true); break;
        default: break;
      }
      return log.status == StartupStatus::Running || log.status == StartupStatus::Reloaded;
    }
    if (process && WaitForSingleObject(process.value, 0) == WAIT_OBJECT_0) {
      setStatus("FAILED", "Game process closed before startup finished.", COL_RED, true);
      return false;
    }
    if (GetTickCount64() >= nextProgress) {
      setStatus("WAITING", "Still loading...", COL_GRAY);
      nextProgress = GetTickCount64() + 15000;
    }
    Sleep(150);
  }
  log.read(nativeLogPath); log.flushLastLine();
  if (log.status != StartupStatus::Waiting) {
    lastStatus = log.status;
    return log.status == StartupStatus::Running || log.status == StartupStatus::Reloaded;
  }
  setStatus("TIMEOUT", "DLL loaded but Slinky never confirmed startup.", COL_RED, true);
  return false;
}

BYTE* moduleBase(DWORD pid, const wchar_t* name, bool fullPath) {
  Handle snap(CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid));
  if (!snap) return nullptr;
  MODULEENTRY32W m{}; m.dwSize = sizeof m;
  if (Module32FirstW(snap.value, &m)) do {
    if (!_wcsicmp(fullPath ? m.szExePath : m.szModule, name)) return m.modBaseAddr;
  } while (Module32NextW(snap.value, &m));
  return nullptr;
}

int inject(DWORD pid, const std::wstring& dll) {
  if (moduleBase(pid, dll.c_str(), true)) { setStatus("SKIPPED", "DLL already loaded.", COL_ORANGE, true); return 2; }
  Handle process(OpenProcess(PROCESS_CREATE_THREAD | PROCESS_VM_OPERATION |
    PROCESS_VM_READ | PROCESS_VM_WRITE | PROCESS_QUERY_INFORMATION, FALSE, pid));
  if (!process) { windowsError(L"Opening game process"); return 0; }
  const SIZE_T sz = (dll.size() + 1) * sizeof(wchar_t);
  void* rPath = VirtualAllocEx(process.value, nullptr, sz, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
  if (!rPath) { windowsError(L"Allocating DLL path in game"); return 0; }
  int result = 0; bool mayFree = true; SIZE_T written = 0;
  const BOOL copied = WriteProcessMemory(process.value, rPath, dll.c_str(), sz, &written);
  if (copied && written == sz) {
    HMODULE lk = GetModuleHandleW(L"kernel32.dll");
    FARPROC ll = lk ? GetProcAddress(lk, "LoadLibraryW") : nullptr;
    BYTE* rk = moduleBase(pid, L"kernel32.dll", false);
    if (lk && ll && rk) {
      auto start = reinterpret_cast<LPTHREAD_START_ROUTINE>(
        rk + (reinterpret_cast<BYTE*>(ll) - reinterpret_cast<BYTE*>(lk)));
      Handle thread(CreateRemoteThread(process.value, nullptr, 0, start, rPath, 0, nullptr));
      if (thread) {
        DWORD wr = WAIT_TIMEOUT; const ULONGLONG began = GetTickCount64();
        while (!cancelled && GetTickCount64() - began < 30000) {
          wr = WaitForSingleObject(thread.value, 150);
          if (wr != WAIT_TIMEOUT) break;
        }
        if (wr == WAIT_OBJECT_0) {
          DWORD ec{};
          if (!GetExitCodeThread(thread.value, &ec)) windowsError(L"Reading DLL loader result");
          const DWORD la = GetFileAttributesW(nativeLogPath.c_str());
          if (ec || (la != INVALID_FILE_ATTRIBUTES && !(la & FILE_ATTRIBUTE_DIRECTORY))) result = 1;
          for (int i = 0; !result && i < 100 && !cancelled; ++i) {
            if (moduleBase(pid, dll.c_str(), true)) { result = 1; break; }
            Sleep(50);
          }
          if (!result) setStatus("ERROR", "DLL not in game after load attempt.", COL_RED, true);
        } else {
          mayFree = false;
          setStatus("TIMEOUT", "Game did not finish loading the DLL.", COL_RED, true);
        }
      } else windowsError(L"Starting DLL loader in game");
    } else windowsError(L"Finding Windows DLL loader", ERROR_PROC_NOT_FOUND);
  } else windowsError(L"Copying DLL path into game", copied ? ERROR_PARTIAL_COPY : GetLastError());
  if (mayFree) VirtualFreeEx(process.value, rPath, 0, MEM_RELEASE);
  return result;
}

BOOL CALLBACK windowTitle(HWND window, LPARAM parameter) {
  if (!IsWindowVisible(window) || !GetWindowTextLengthW(window)) return TRUE;
  DWORD pid = 0; GetWindowThreadProcessId(window, &pid);
  auto& list = *reinterpret_cast<std::vector<Candidate>*>(parameter);
  for (auto& c : list) if (c.pid == pid && !c.title[0]) {
    GetWindowTextW(window, c.title, static_cast<int>(std::size(c.title))); break;
  }
  return TRUE;
}

std::vector<Candidate> enumerateCandidates() {
  std::vector<Candidate> result;
  Handle snap(CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
  if (!snap) return result;
  PROCESSENTRY32W p{}; p.dwSize = sizeof p;
  if (Process32FirstW(snap.value, &p)) do {
    if (result.size() < 64 && (!_wcsicmp(p.szExeFile, L"java.exe") ||
                               !_wcsicmp(p.szExeFile, L"javaw.exe"))) {
      Candidate e{}; e.pid = p.th32ProcessID;
      wcsncpy_s(e.executable, p.szExeFile, _TRUNCATE);
      result.push_back(e);
    }
  } while (Process32NextW(snap.value, &p));
  EnumWindows(windowTitle, reinterpret_cast<LPARAM>(&result));
  result.erase(std::remove_if(result.begin(), result.end(),
    [](const auto& c) { return !c.title[0]; }), result.end());
  return result;
}

bool extractNative() {
  HRSRC res = FindResourceW(nullptr, MAKEINTRESOURCEW(200), RT_RCDATA);
  if (!res) { windowsError(L"Finding bundled DLL"); return false; }
  DWORD size = SizeofResource(nullptr, res);
  HGLOBAL loaded = LoadResource(nullptr, res);
  const void* bytes = loaded ? LockResource(loaded) : nullptr;
  if (!bytes || !size) { windowsError(L"Reading bundled DLL", ERROR_INVALID_DATA); return false; }
  wchar_t tmp[MAX_PATH]{}; DWORD tl = GetTempPathW(MAX_PATH, tmp);
  if (!tl || tl >= MAX_PATH) { windowsError(L"Finding temp folder"); return false; }
  wchar_t dir[MAX_PATH]{};
  _snwprintf_s(dir, _TRUNCATE, L"%lsslinky-%lu", tmp, GetCurrentProcessId());
  if (!CreateDirectoryW(dir, nullptr) && GetLastError() != ERROR_ALREADY_EXISTS) {
    windowsError(L"Creating temp folder"); return false;
  }
  payloadPath   = std::wstring(dir) + L"\\SlinkyNative.dll";
  nativeLogPath = std::wstring(dir) + L"\\slinky-native.log";
  if (!DeleteFileW(nativeLogPath.c_str()) && GetLastError() != ERROR_FILE_NOT_FOUND) {
    windowsError(L"Clearing previous log"); return false;
  }
  Handle file(CreateFileW(payloadPath.c_str(), GENERIC_WRITE, 0, nullptr,
    CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr));
  if (!file) { windowsError(L"Writing bundled DLL"); return false; }
  DWORD written{};
  if (!WriteFile(file.value, bytes, size, &written, nullptr) || written != size) {
    windowsError(L"Extracting bundled DLL"); return false;
  }
  return true;
}

std::vector<std::wstring> parseArgs() {
  std::vector<std::wstring> result;
  const wchar_t* cur = GetCommandLineW();
  while (*cur) {
    while (*cur == L' ' || *cur == L'\t') ++cur;
    if (!*cur) break;
    std::wstring arg; bool q = false;
    while (*cur && (q || (*cur != L' ' && *cur != L'\t'))) {
      if (*cur == L'"') q = !q; else arg += *cur; ++cur;
    }
    result.push_back(arg);
  }
  return result;
}

int doInject(DWORD pid) {
  char msg[64]; snprintf(msg, sizeof(msg), "Found Minecraft — PID: %lu", pid);
  setStatus("FOUND", msg, COL_GREEN);
  Sleep(800);
  setStatus("INJECTING", "INJECTING ...", COL_ORANGE);
  Sleep(500);
  ULONGLONG started = GetTickCount64();
  const int loaded = inject(pid, payloadPath);
  if (loaded == 1 && waitForStartup(pid, started)) {
    setStatus("READY", "Injected successfully", COL_GREEN);
    Sleep(600);
    for (int i = 3; i >= 1; i--) {
      char countdown[48];
      snprintf(countdown, sizeof(countdown), "Closing in %d second%s", i, i == 1 ? "" : "s");
      setStatus("READY", countdown, COL_GREEN);
      Sleep(1000);
    }
    return 0;
  }
  if (loaded == 2 || lastStatus == StartupStatus::AlreadyRunning) return 2;
  return 1;
}

} // namespace

int WINAPI WinMain(HINSTANCE, HINSTANCE, LPSTR, int) {
  setupConsole();
  InitializeCriticalSection(&g_cs);

  const auto args = parseArgs();
  const bool noPause = args.size() == 2 && args[1] == L"--no-pause";

  // Print the static first-frame banner, then kick off the animation thread
  setStatus("SCANNING", "Scanning for Minecraft...", COL_GRAY);
  startAnim();

  if (!extractNative()) {
    setStatus("FAILED", "Could not extract bundled DLL.", COL_RED, true);
    Sleep(2000);
    DeleteCriticalSection(&g_cs);
    return 1;
  }

  candidates = enumerateCandidates();

  if (candidates.empty()) {
    setStatus("NOT FOUND", "Please run Minecraft first.", COL_RED, true);
    Sleep(2000);
    DeleteCriticalSection(&g_cs);
    return 1;
  }

  int r;
  if (candidates.size() == 1) {
    r = doInject(candidates.front().pid);
  } else {
    // Multiple — stop animation, list them, ask user
    stopAnim();
    printf(COL_ORANGE "\n\n  Multiple Minecraft windows found:\n\n" COL_RESET);
    for (size_t i = 0; i < candidates.size(); ++i) {
      int sz = WideCharToMultiByte(CP_UTF8, 0, candidates[i].title, -1, nullptr, 0, nullptr, nullptr);
      std::string title(sz, '\0');
      WideCharToMultiByte(CP_UTF8, 0, candidates[i].title, -1, title.data(), sz, nullptr, nullptr);
      printf(COL_WHITE "  [%zu] PID %-6lu  %s\n" COL_RESET, i + 1, candidates[i].pid, title.c_str());
    }
    printf(COL_GRAY "\n  Enter number: " COL_RESET);
    int choice = 0;
    if (scanf_s("%d", &choice) != 1 || choice < 1 || choice > static_cast<int>(candidates.size())) {
      printf(COL_RED "  Invalid selection.\n" COL_RESET);
      if (!noPause) { printf("\n  Press Enter to exit.\n"); getchar(); }
      DeleteCriticalSection(&g_cs);
      return 1;
    }
    // restart animation for injection phase
    setStatus("FOUND", "Selected.", COL_GREEN);
    startAnim();
    r = doInject(candidates[static_cast<size_t>(choice - 1)].pid);
  }

  DeleteCriticalSection(&g_cs);
  return r;
}
