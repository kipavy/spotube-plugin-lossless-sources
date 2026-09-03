// Vendored from KRTirtho/hetu_spotube_plugin so the harness can bind the same
// LocalStorage and SpotubeForm classes Spotube binds, without pulling in the
// Flutter-only parts of that package. Keep in sync if the upstream bindings
// change.

import 'package:hetu_script/binding.dart';
import 'package:hetu_script/hetu_script.dart';
import 'localstorage.dart';

class LocalStorageClassBinding extends HTExternalClass {
  final Localstorage localStorageImpl;
  LocalStorageClassBinding({required this.localStorageImpl})
    : super('LocalStorage');

  @override
  dynamic memberGet(String varName, {String? from}) {
    switch (varName) {
      case 'LocalStorage.setString':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.setString(positionalArgs[0], positionalArgs[1]);
      case 'LocalStorage.getString':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.getString(positionalArgs[0]);
      case 'LocalStorage.remove':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.remove(positionalArgs[0]);
      case 'LocalStorage.clear':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.clear();
      case 'LocalStorage.containsKey':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.containsKey(positionalArgs[0]);
      case 'LocalStorage.setInt':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.setInt(positionalArgs[0], positionalArgs[1]);
      case 'LocalStorage.getInt':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.getInt(positionalArgs[0]);
      case 'LocalStorage.setDouble':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.setDouble(positionalArgs[0], positionalArgs[1]);
      case 'LocalStorage.getDouble':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.getDouble(positionalArgs[0]);
      case 'LocalStorage.setBool':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.setBool(positionalArgs[0], positionalArgs[1]);
      case 'LocalStorage.getBool':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.getBool(positionalArgs[0]);
      case 'LocalStorage.setStringList':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.setStringList(
          positionalArgs[0],
          positionalArgs[1],
        );
      case 'LocalStorage.getStringList':
        return (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) => localStorageImpl.getStringList(positionalArgs[0]);
      default:
        throw HTError.undefined(varName);
    }
  }
}
