// Manual smoke test: runs the compiled plugin against the real archive.org
// and checks that the URL it produces is really downloadable. Not part of
// `make test` -- it needs the network and depends on what the Archive holds
// today. Run it with: dart run bin/smoke_archive.dart
import 'dart:io';

import 'package:hetu_script/hetu_script.dart';
import 'package:hetu_std/hetu_std.dart';
import 'package:plugin_harness/localstorage.binding.dart';
import 'package:plugin_harness/localstorage.dart';

class Mem implements Localstorage {
  final Map<String, dynamic> s = {};
  @override
  Future<void> setString(String k, String v) async => s[k] = v;
  @override
  Future<String?> getString(String k) async => s[k] as String?;
  @override
  Future<void> remove(String k) async => s.remove(k);
  @override
  Future<void> clear() async => s.clear();
  @override
  Future<bool> containsKey(String k) async => s.containsKey(k);
  @override
  Future<void> setInt(String k, int v) async => s[k] = v;
  @override
  Future<int?> getInt(String k) async => s[k] as int?;
  @override
  Future<void> setDouble(String k, double v) async => s[k] = v;
  @override
  Future<double?> getDouble(String k) async => s[k] as double?;
  @override
  Future<void> setBool(String k, bool v) async => s[k] = v;
  @override
  Future<bool?> getBool(String k) async => s[k] as bool?;
  @override
  Future<void> setStringList(String k, List<String> v) async => s[k] = v;
  @override
  Future<List<String>?> getStringList(String k) async =>
      (s[k] as List?)?.cast<String>();
}

Future<void> main() async {
  final hetu = Hetu(config: HetuConfig(printPerformanceStatistics: false));
  hetu.init();
  HetuStdLoader.loadBindings(hetu);
  hetu.interpreter
      .bindExternalClass(LocalStorageClassBinding(localStorageImpl: Mem()));
  await HetuStdLoader.loadBytecodePureDart(hetu, '.deps/hetu_std');
  hetu.loadBytecode(
    bytes: File('.deps/hetu_spotube_plugin/lib/assets/bytecode/spotube_plugin.out')
        .readAsBytesSync(),
    moduleName: 'spotube_plugin',
  );
  hetu.loadBytecode(
    bytes: File('../build/plugin.out').readAsBytesSync(),
    moduleName: 'plugin',
    globallyImport: true,
  );

  final plugin = hetu.invoke('LosslessSourcesPlugin');
  final audioSource = plugin.memberGet('audioSource');
  plugin.memberGet('sources').memberSet('cached',
      await hetu.eval('[{"type": "archive", "base": "https://archive.org"}]'));

  // Two shapes of request: a live recording, and a mainstream studio track.
  final wanted = <Map<String, String>>[
    {'name': 'Scarlet Begonias', 'artist': 'Grateful Dead'},
    {'name': 'One More Time', 'artist': 'Daft Punk'},
    {'name': 'Bohemian Rhapsody', 'artist': 'Queen'},
  ];
  for (final want in wanted) {
    final t = await hetu.eval(
        '{ "name": "${want['name']}", "isrc": "", "artists": [{ "name": "${want['artist']}" }] }');
    final m = await audioSource.invoke('matches', positionalArgs: [t]) as List;
    print('${want['artist']} - ${want['name']}: ${m.length} match(es)'
        '${m.isEmpty ? '' : ' -> ${m.first['id']}'}');
  }

  final track = await hetu.eval(
      '{ "name": "One More Time", "isrc": "", "artists": [{ "name": "Daft Punk" }] }');

  final matches =
      await audioSource.invoke('matches', positionalArgs: [track]) as List;
  print('matches: ${matches.length}');
  if (matches.isEmpty) {
    print('NO MATCH');
    exit(1);
  }
  print('first: ${matches.first}');

  final streams =
      await audioSource.invoke('streams', positionalArgs: [matches.first])
          as List;
  print('streams: $streams');

  final client = HttpClient();
  final request = await client.getUrl(Uri.parse(streams.first['url'] as String));
  request.headers.add('range', 'bytes=0-1024');
  final response = await request.close();
  await response.drain();
  print('GET -> ${response.statusCode} ${response.headers.contentType}');
  exit(response.statusCode == 200 || response.statusCode == 206 ? 0 : 1);
}
