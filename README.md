## Wolkenpumpe

### statement

Wolkenpumpe is a live improvisation interface based on ScalaCollider / SoundProcesses / Prefuse. It is (C)opyright 2008-2012 by Hanns Holger Rutz. All rights reserved. Wolkenpumpe is released under the [GNU General Public License](http://github.com/Sciss/Wolkenpumpe/blob/master/licenses/Wolkenpumpe-License.txt) and comes with absolutely no warranties. To contact the author, send an email to `contact at sciss.de`.

Strictly speaking, this is Wolkenpumpe 5th generation. For generation four, based on SuperCollider and SwingOSC, see [sourceforge.net/projects/tintantmare](http://sourceforge.net/projects/tintantmare/).

### requirements

Builds with xsbt (sbt 0.11) against Scala 2.9.2. Depends on [ScalaCollider](http://github.com/Sciss/ScalaCollider) and [SoundProcesses](http://github.com/Sciss/SoundProcesses). Standard sbt targets are `clean`, `update`, `compile`, `package`, `doc`, `publish-local`.

### creating an IntelliJ IDEA project

To develop the sources, if you haven't globally installed the sbt-idea plugin yet, create the following contents in `~/.sbt/plugins/build.sbt`:

    resolvers += "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"
    
    addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.0.0")

Then to create the IDEA project, run the following two commands from the xsbt shell:

    > set ideaProjectName := "Wolkenpumpe"
    > gen-idea
