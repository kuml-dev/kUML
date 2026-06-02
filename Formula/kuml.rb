class Kuml < Formula
  desc "Kotlin-based UML/C4 modelling and rendering tool"
  homepage "https://github.com/kuml-dev/kuml"

  # NOTE: url and sha256 are placeholders updated by the release workflow.
  # On every `v*.*.*` tag, .github/workflows/release.yml prints the new
  # values in its summary; copy them here and into the kuml-dev/homebrew-kuml
  # tap repo.
  url "https://github.com/kuml-dev/kuml/releases/download/v0.1.0/kuml-cli-0.1.0.zip"
  sha256 "REPLACE_ON_RELEASE_WITH_ACTUAL_SHA256"
  version "0.1.0"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    bin.write_jar_script libexec/"lib/kuml.jar", "kuml", java_version: "21"
  end

  test do
    assert_match "kuml", shell_output("#{bin}/kuml --help")
  end
end
