// A stand-in for the YouTubeEngine Spotube binds into the plugin runtime.
//
// The Dart class must be called YouTubeEngine, not something like FakeYouTube:
// Hetu resolves the type name of whatever a .then callback receives, so a
// differently named class fails at runtime with "Undefined identifier".
//
// Shaped after KRTirtho/hetu_spotube_plugin's YouTubeEngineClassBinding: the
// script calls YouTubeEngine(), then search / getVideo / streamManifest on the
// instance. Here those return canned data so the router can be tested without
// touching YouTube.

import 'package:hetu_script/binding.dart';
import 'package:hetu_script/hetu_script.dart';

class YouTubeEngine {
  /// Every query the script searched for, in order, so a test can assert that
  /// the ISRC was tried before the title.
  final List<String> queries = [];

  /// Video ids the script asked for streams of.
  final List<String> manifests = [];

  /// Results keyed by query; anything not listed comes back empty.
  final Map<String, List<Map<String, dynamic>>> results;

  final List<Map<String, dynamic>> streams;

  YouTubeEngine({required this.results, required this.streams});

  Future<List<Map<String, dynamic>>> search(String query) async {
    queries.add(query);
    return results[query] ?? const [];
  }

  Future<Map<String, dynamic>> getVideo(String videoId) async => {'id': videoId};

  Future<List<Map<String, dynamic>>> streamManifest(String videoId) async {
    manifests.add(videoId);
    return streams;
  }
}

class FakeYouTubeClassBinding extends HTExternalClass {
  final YouTubeEngine engine;

  FakeYouTubeClassBinding(this.engine) : super('YouTubeEngine');

  @override
  dynamic memberGet(String varName, {String? from}) {
    return switch (varName) {
      'YouTubeEngine' => (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) =>
            engine,
      _ => HTError.undefined(varName),
    };
  }

  @override
  instanceMemberGet(object, String varName) {
    final target = object as YouTubeEngine;
    return switch (varName) {
      'search' => (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) =>
            target.search(positionalArgs[0] as String),
      'getVideo' => (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) =>
            target.getVideo(positionalArgs[0] as String),
      'streamManifest' => (
          HTEntity entity, {
          List<dynamic> positionalArgs = const [],
          Map<String, dynamic> namedArgs = const {},
          List<HTType> typeArgs = const [],
        }) =>
            target.streamManifest(positionalArgs[0] as String),
      _ => throw HTError.undefined(varName),
    };
  }
}
