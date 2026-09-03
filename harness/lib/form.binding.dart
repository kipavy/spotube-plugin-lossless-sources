// Vendored from KRTirtho/hetu_spotube_plugin so the harness can bind the same
// LocalStorage and SpotubeForm classes Spotube binds, without pulling in the
// Flutter-only parts of that package. Keep in sync if the upstream bindings
// change.

import 'package:hetu_script/external/external_class.dart';
import 'package:hetu_script/hetu_script.dart';

class SpotubeFormClassBinding extends HTExternalClass {
  Future<List<Map<String, dynamic>>?> Function(
    String title,
    List<Map<String, dynamic>> fields,
  )
  onShow;
  SpotubeFormClassBinding({required this.onShow}) : super("SpotubeForm");

  @override
  dynamic memberGet(String varName, {String? from}) {
    return switch (varName) {
      "SpotubeForm.show" => (
        HTEntity entity, {
        List<dynamic> positionalArgs = const [],
        Map<String, dynamic> namedArgs = const {},
        List<HTType> typeArgs = const [],
      }) {
        return onShow(
          positionalArgs[0] as String,
          (positionalArgs[1] as List).cast<Map<String, dynamic>>(),
        );
      },
      _ => HTError.undefined(varName),
    };
  }
}
