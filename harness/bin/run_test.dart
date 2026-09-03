import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:hetu_script/hetu_script.dart';
import 'package:hetu_std/hetu_std.dart';
import 'package:plugin_harness/form.binding.dart';
import 'package:plugin_harness/localstorage.binding.dart';
import 'package:plugin_harness/localstorage.dart';

/// In-memory LocalStorage so the plugin's persistence can be inspected.
class MemoryLocalstorage implements Localstorage {
  final Map<String, dynamic> store = {};

  @override
  Future<void> setString(String key, String value) async => store[key] = value;
  @override
  Future<String?> getString(String key) async => store[key] as String?;
  @override
  Future<void> remove(String key) async => store.remove(key);
  @override
  Future<void> clear() async => store.clear();
  @override
  Future<bool> containsKey(String key) async => store.containsKey(key);
  @override
  Future<void> setInt(String key, int value) async => store[key] = value;
  @override
  Future<int?> getInt(String key) async => store[key] as int?;
  @override
  Future<void> setDouble(String key, double value) async => store[key] = value;
  @override
  Future<double?> getDouble(String key) async => store[key] as double?;
  @override
  Future<void> setBool(String key, bool value) async => store[key] = value;
  @override
  Future<bool?> getBool(String key) async => store[key] as bool?;
  @override
  Future<void> setStringList(String key, List<String> value) async =>
      store[key] = value;
  @override
  Future<List<String>?> getStringList(String key) async {
    final value = store[key];
    if (value == null) return null;
    return (value as List).map((e) => e.toString()).toList();
  }
}

/// Set once every fake server is listening, so the published list the plugin
/// fetches names their real ports.
late Map<String, dynamic> Function() sourcesDocument;

abstract class FakeServer {
  late HttpServer server;
  final List<String> requests = [];

  String get base => 'http://127.0.0.1:${server.port}';

  Future<void> handle(HttpRequest request);

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    unawaited(() async {
      await for (final request in server) {
        requests.add('${request.uri.path}?${request.uri.query}');
        request.response.headers.contentType = ContentType.json;

        if (request.uri.path == '/sources.json') {
          // Same content type raw.githubusercontent serves .json with, so the
          // client hands the plugin an undecoded string exactly as in the app.
          request.response.headers.contentType =
              ContentType('text', 'plain', charset: 'utf-8');
          request.response.write(jsonEncode(sourcesDocument()));
          await request.response.close();
          continue;
        }

        await handle(request);
      }
    }());
  }

  Future<void> stop() => server.close(force: true);
}

/// A hifi-api instance. `alive: false` answers 503 like a blocked account,
/// `busyResponses` answers 202 like an instance whose accounts are all in use,
/// and `hasCatalogue: false` answers searches with nothing.
class FakeHifi extends FakeServer {
  final bool alive;
  final int busyResponses;
  final bool hasCatalogue;

  final Map<String, int> trackCalls = {};

  FakeHifi({
    this.alive = true,
    this.busyResponses = 0,
    this.hasCatalogue = true,
  });

  static String btsManifest() {
    final manifest = jsonEncode({
      'mimeType': 'audio/flac',
      'codecs': 'flac',
      'urls': ['https://cdn.example/track.flac'],
    });
    return base64.encode(utf8.encode(manifest));
  }

  @override
  Future<void> handle(HttpRequest request) async {
    if (!alive) {
      request.response.statusCode = 503;
      request.response.write(jsonEncode({'detail': 'All accounts are inactive'}));
      await request.response.close();
      return;
    }

    if (request.uri.path == '/search/') {
      request.response.write(jsonEncode({
        'version': '2.10',
        'data': {
          'limit': 20,
          'items': hasCatalogue
              ? [
                  {
                    'id': 1550546,
                    'title': 'One More Time',
                    'duration': 320,
                    'isrc': 'GBDUW0000053',
                    'artist': {'name': 'Daft Punk'},
                    'artists': [
                      {'name': 'Daft Punk'}
                    ],
                    'album': {
                      'title': 'Discovery',
                      'cover': 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                    },
                  },
                ]
              : [],
        },
      }));
      await request.response.close();
      return;
    }

    if (request.uri.path == '/track/') {
      final quality = request.uri.queryParameters['quality'] ?? '';
      final seen = (trackCalls[quality] ?? 0) + 1;
      trackCalls[quality] = seen;

      if (seen <= busyResponses) {
        request.response.statusCode = 202;
        request.response.write(jsonEncode({'detail': 'All accounts are busy'}));
        await request.response.close();
        return;
      }

      request.response.write(jsonEncode({
        'version': '2.10',
        'data': {
          'manifestMimeType': 'application/vnd.tidal.bts',
          'manifest': btsManifest(),
          'audioQuality': 'LOSSLESS',
          'sampleRate': 44100,
          'bitDepth': 16,
        },
      }));
      await request.response.close();
      return;
    }

    request.response.statusCode = 404;
    request.response.write(jsonEncode({'detail': 'not found'}));
    await request.response.close();
  }
}

/// An Internet Archive mirror: a search that returns one item, and an item
/// whose file list holds a matching FLAC among files that must be ignored.
///
/// `answerCreatorSearch: false` makes it behave like an upload that left
/// `creator` as the uploader -- findable by free text only.
class FakeArchive extends FakeServer {
  final bool answerCreatorSearch;
  final List<String> queries = [];

  FakeArchive({this.answerCreatorSearch = true});

  @override
  Future<void> handle(HttpRequest request) async {
    if (request.uri.path == '/advancedsearch.php') {
      final query = request.uri.queryParameters['q'] ?? '';
      queries.add(query);

      final isCreatorSearch = query.startsWith('creator:');
      final docs = (isCreatorSearch && !answerCreatorSearch)
          ? []
          : [
              {'identifier': 'gd1977-05-08'},
            ];

      request.response.write(jsonEncode({
        'response': {'numFound': docs.length, 'docs': docs},
      }));
      await request.response.close();
      return;
    }

    if (request.uri.path == '/metadata/gd1977-05-08') {
      request.response.write(jsonEncode({
        'metadata': {
          'title': 'Barton Hall 1977',
          'creator': 'Grateful Dead',
        },
        'files': [
          {'name': 'cover.jpg', 'title': 'Cover'},
          {'name': 'gd77-05-08d1t01.mp3', 'title': 'Scarlet Begonias'},
          {
            'name': 'gd77-05-08d1t01 Scarlet Begonias.flac',
            'title': 'Scarlet Begonias',
            'length': '674.5',
          },
          {'name': 'gd77-05-08d1t02.flac', 'title': 'Fire On The Mountain'},
        ],
      }));
      await request.response.close();
      return;
    }

    request.response.statusCode = 404;
    request.response.write(jsonEncode({'detail': 'not found'}));
    await request.response.close();
  }
}

final failures = <String>[];

void check(String name, bool condition, [String detail = '']) {
  if (condition) {
    print('  PASS  $name');
  } else {
    failures.add(name);
    print('  FAIL  $name${detail.isEmpty ? '' : ' -- $detail'}');
  }
}

/// The plugin should expose no authentication segment: there is nothing to
/// configure. memberGet throws rather than returning null when it is absent.
bool pluginHasMember(dynamic plugin, String name) {
  try {
    plugin.memberGet(name);
    return true;
  } catch (_) {
    return false;
  }
}

Future<void> main(List<String> args) async {
  final pluginDir = args.isNotEmpty ? args[0] : '..';
  final hstdDir = args.length > 1 ? args[1] : '.deps/hetu_std';
  final spotubeModule = args.length > 2 ? args[2] : '.deps/hetu_spotube_plugin';

  final dead = FakeHifi(alive: false);
  final live = FakeHifi(busyResponses: 2);
  final archive = FakeArchive();
  await dead.start();
  await live.start();
  await archive.start();

  final storage = MemoryLocalstorage();

  // Deliberately messy and deliberately mixed: a trailing slash, a duplicate,
  // a junk entry and a type no installed plugin knows yet.
  sourcesDocument = () => {
        'updated': '2026-09-03T00:00:00Z',
        'sources': [
          {'type': 'hifi-api', 'base': '  ${dead.base}/ '},
          {'type': 'hifi-api', 'base': live.base},
          {'type': 'hifi-api', 'base': live.base},
          {'type': 'hifi-api', 'base': 'not-a-url'},
          {'type': 'some-future-source', 'base': 'https://unknown.example'},
          {'type': 'archive', 'base': archive.base},
        ],
      };

  var formShown = 0;

  // Bytecode only, exactly as Spotube loads a packaged plugin -- no source
  // context, so nothing here can accidentally pass by reading the .ht files.
  final hetu = Hetu(config: HetuConfig(printPerformanceStatistics: false));
  hetu.init();

  HetuStdLoader.loadBindings(hetu);
  hetu.interpreter
      .bindExternalClass(LocalStorageClassBinding(localStorageImpl: storage));
  hetu.interpreter.bindExternalClass(SpotubeFormClassBinding(
    onShow: (title, fields) async {
      // Bound so a stray form call is caught rather than silently ignored.
      formShown += 1;
      return null;
    },
  ));

  await HetuStdLoader.loadBytecodePureDart(hetu, hstdDir);
  hetu.loadBytecode(
    bytes: File('$spotubeModule/lib/assets/bytecode/spotube_plugin.out')
        .readAsBytesSync(),
    moduleName: 'spotube_plugin',
  );
  hetu.loadBytecode(
    bytes: File('$pluginDir/build/plugin.out').readAsBytesSync(),
    moduleName: 'plugin',
    globallyImport: true,
  );

  print('\nplugin constructs');
  final plugin = hetu.invoke('HifiTidalAudioSourcePlugin');
  check('entry point instantiates', plugin != null);
  check('no authentication segment is exposed', !pluginHasMember(plugin, 'auth'),
      'the plugin should need no configuration at all');
  check('the form was never shown', formShown == 0);

  final audioSource = plugin.memberGet('audioSource');
  final store = plugin.memberGet('sources');
  store.memberSet('remoteUrl', '${live.base}/sources.json');

  print('\npublished list is fetched and normalized');
  final resolved = await store.invoke('resolve') as List;
  check(
      'published entries come first, in order',
      resolved.take(2).map((e) => e['base']).join(',') ==
          '${dead.base},${live.base}',
      'resolved=$resolved');
  check('junk entry dropped and duplicates merged',
      resolved.where((e) => e['type'] == 'hifi-api').length == 2 + 2,
      'resolved=$resolved');
  check('unknown source types are kept for newer plugins',
      resolved.any((e) => e['type'] == 'some-future-source'),
      'resolved=$resolved');
  final cache = storage.store['sources-cache'] as String?;
  check(
      'the list was cached with a timestamp',
      cache != null &&
          int.tryParse(cache.split('|').first) != null &&
          cache.contains('hifi-api=${dead.base}'),
      'cache=$cache');

  print('\nrouter reaches the first source that has the track');
  final track = await hetu.eval('''
    { "name": "One More Time", "isrc": "GBDUW0000053",
      "artists": [{ "name": "Daft Punk" }] }
  ''');
  final matches = await audioSource.invoke('matches', positionalArgs: [track]);
  check('one match returned', matches is List && matches.length == 1,
      'matches=$matches');
  final match = (matches as List).first;
  check('match is tagged with its source', match['id'] == 'hifi-api:1550546',
      'match=$match');
  check('match carries the isrc', match['isrc'] == 'GBDUW0000053');
  check('the dead instance was tried first', dead.requests.isNotEmpty,
      'requests=${dead.requests}');
  check('search failed over to the live instance',
      live.requests.any((r) => r.startsWith('/search/')),
      'requests=${live.requests}');
  check('the archive was not consulted once tidal answered',
      !archive.requests.any((r) => r.startsWith('/advancedsearch')),
      'requests=${archive.requests}');

  print('\nstreams() routes back to the source that matched, and retries 202');
  final startedAt = DateTime.now();
  final streams = await audioSource.invoke('streams', positionalArgs: [match]);
  final elapsed = DateTime.now().difference(startedAt);
  check('a stream is returned', streams is List && streams.isNotEmpty,
      'streams=$streams');
  check('202 was retried on the same instance',
      (live.trackCalls['HI_RES_LOSSLESS'] ?? 0) == 3,
      'calls=${live.trackCalls}');
  check('retries waited for the backoff', elapsed.inSeconds >= 6,
      'elapsed=${elapsed.inSeconds}s');
  final stream = (streams as List).first;
  check('flac stream is reported lossless', stream['container'] == 'flac',
      'stream=$stream');
  check('stream url comes from the decoded manifest',
      stream['url'] == 'https://cdn.example/track.flac', 'stream=$stream');
  check('playback failed over past the blocked instance',
      dead.requests.where((r) => r.startsWith('/track/')).length == 3,
      'requests=${dead.requests}');

  print('\nan id from an older version still resolves');
  final legacy = await hetu.eval('{ "id": "1550546" }');
  final legacyStreams =
      await audioSource.invoke('streams', positionalArgs: [legacy]);
  check('unprefixed ids are treated as hifi-api',
      legacyStreams is List && legacyStreams.isNotEmpty,
      'streams=$legacyStreams');

  print('\nthe archive answers when no proxy has the track');
  // Every hifi-api instance now returns an empty catalogue, which is the state
  // the plugin is actually in today.
  final barren = FakeHifi(hasCatalogue: false);
  await barren.start();
  sourcesDocument = () => {
        'sources': [
          {'type': 'hifi-api', 'base': barren.base},
          {'type': 'archive', 'base': archive.base},
        ],
      };
  storage.store.clear();
  store.memberSet('remoteUrl', '${barren.base}/sources.json');
  // Pinned rather than resolved: resolve() always appends the bundled
  // defaults, and those are real hosts on the internet. Merging is covered
  // above; what is under test here is the router.
  store.memberSet(
      'cached',
      await hetu.eval('[{"type": "hifi-api", "base": "${barren.base}"},'
          '{"type": "archive", "base": "${archive.base}"}]'));

  final live77 = await hetu.eval('''
    { "name": "Scarlet Begonias", "isrc": "",
      "artists": [{ "name": "Grateful Dead" }] }
  ''');
  final archiveMatches =
      await audioSource.invoke('matches', positionalArgs: [live77]);
  check('the archive produced a match',
      archiveMatches is List && archiveMatches.isNotEmpty,
      'matches=$archiveMatches');
  final archiveMatch = (archiveMatches as List).first;
  check(
      'only the matching flac was taken',
      archiveMatch['id'] ==
          'archive:gd1977-05-08|gd77-05-08d1t01 Scarlet Begonias.flac',
      'match=$archiveMatch');
  check('the item title became the album',
      archiveMatch['album'] == 'Barton Hall 1977', 'match=$archiveMatch');
  check('length was read as milliseconds', archiveMatch['duration'] == 674000,
      'match=$archiveMatch');

  final archiveStreams =
      await audioSource.invoke('streams', positionalArgs: [archiveMatch]);
  check(
      'the archive stream is a direct flac url',
      (archiveStreams as List).first['url'] ==
          '${archive.base}/download/gd1977-05-08/gd77-05-08d1t01%20Scarlet%20Begonias.flac',
      'streams=$archiveStreams');

  print('\nfree-text search catches uploads the creator search misses');
  final looseArchive = FakeArchive(answerCreatorSearch: false);
  await looseArchive.start();
  store.memberSet(
      'cached',
      await hetu.eval('[{"type": "archive", "base": "${looseArchive.base}"}]'));
  final looseMatches =
      await audioSource.invoke('matches', positionalArgs: [live77]) as List;
  check('a match was still found', looseMatches.isNotEmpty,
      'matches=$looseMatches');
  check('the creator search ran first',
      looseArchive.queries.first.startsWith('creator:'),
      'queries=${looseArchive.queries}');
  check(
      'the fallback searched artist and title as free text',
      looseArchive.queries.length == 2 &&
          looseArchive.queries[1].startsWith('"Grateful Dead" AND "Scarlet Begonias"'),
      'queries=${looseArchive.queries}');

  print('\ncaching and fallbacks');
  // Fetch once for real so a cache exists to fall back to.
  store.memberSet('cached', null);
  await store.invoke('resolve');

  // A connection error inside the fetch cannot be caught in Hetu, so an
  // offline start must not reach for the network while the cache is fresh.
  final listRequestsBefore =
      barren.requests.where((r) => r.startsWith('/sources.json')).length;
  store.memberSet('remoteUrl', 'http://127.0.0.1:1/sources.json');
  store.memberSet('cached', null);
  final fromCache = await store.invoke('resolve') as List;
  check('cached list is used when the published one is unreachable',
      fromCache.first['base'] == barren.base, 'fromCache=$fromCache');
  check(
      'no fetch was attempted while the cache was fresh',
      barren.requests.where((r) => r.startsWith('/sources.json')).length ==
          listRequestsBefore,
      'requests=${barren.requests}');

  // A 404 rather than a refused connection: Dio throws on connection errors
  // and Hetu cannot catch that, so only the answered-but-useless case is
  // recoverable. See the README's note on offline first runs.
  storage.store.clear();
  store.memberSet('remoteUrl', '${barren.base}/missing.json');
  store.memberSet('cached', null);
  final defaults = await store.invoke('resolve') as List;
  check('bundled defaults are all that is left',
      defaults.length == 3 && defaults.last['type'] == 'archive',
      'defaults=$defaults');
  check('the archive is bundled, so something always answers',
      defaults.any((e) => e['base'] == 'https://archive.org'),
      'defaults=$defaults');

  await dead.stop();
  await live.stop();
  await barren.stop();
  await archive.stop();
  await looseArchive.stop();

  print('');
  if (failures.isEmpty) {
    print('RESULT: all runtime checks passed');
    exit(0);
  }
  print('RESULT: ${failures.length} failed -- ${failures.join(', ')}');
  exit(1);
}
