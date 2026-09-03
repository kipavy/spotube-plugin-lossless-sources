// Vendored from KRTirtho/hetu_spotube_plugin so the harness can bind the same
// LocalStorage and SpotubeForm classes Spotube binds, without pulling in the
// Flutter-only parts of that package. Keep in sync if the upstream bindings
// change.

abstract interface class Localstorage {
  Future<void> setString(String key, String value);
  Future<String?> getString(String key);
  Future<void> remove(String key);
  Future<void> clear();
  Future<bool> containsKey(String key);
  Future<void> setInt(String key, int value);
  Future<int?> getInt(String key);
  Future<void> setDouble(String key, double value);
  Future<double?> getDouble(String key);
  Future<void> setBool(String key, bool value);
  Future<bool?> getBool(String key);
  Future<void> setStringList(String key, List<String> value);
  Future<List<String>?> getStringList(String key);
}
