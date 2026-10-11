__declspec(dllimport) int __stdcall lstrlenA(const char *s);
__declspec(dllimport) void __stdcall OutputDebugStringA(const char *s);

static const char *names[] = {"alpha", "beta", "gamma", "delta"};

__declspec(dllexport) int checksum(const char *s) {
  int n = lstrlenA(s);
  int c = 0;
  for (int i = 0; i < n; i++) c = c * 31 + s[i];
  return c;
}

__declspec(dllexport) const char *pick(int i) {
  if (i < 0 || i > 3) {
    OutputDebugStringA("pick: index out of range");
    return 0;
  }
  return names[i];
}

int __stdcall DllEntry(void *h, unsigned reason, void *reserved) { return 1; }
