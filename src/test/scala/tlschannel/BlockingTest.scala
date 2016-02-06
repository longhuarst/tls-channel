package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.util.Random
import java.io.IOException
import TestUtil.functionToRunnable
import javax.net.ssl.SSLContext
import java.nio.channels.ByteChannel
import java.nio.ByteBuffer
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SSLEngine
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import TestUtil.StreamWithTakeWhileInclusive
import com.typesafe.scalalogging.slf4j.StrictLogging

class BlockingTest extends FunSuite with Matchers with StrictLogging {

  val port = 7777

  val localhost = InetAddress.getByName(null)

  val address = new InetSocketAddress(localhost, port)

  val sslContext = SSLContext.getDefault

  private def createSslEngine(cipher: String, client: Boolean): SSLEngine = {
    val engine = sslContext.createSSLEngine()
    engine.setUseClientMode(client)
    engine.setEnabledCipherSuites(Array(cipher))
    engine
  }

  def nioNio(
    cipher: String,
    externalClientChunkSize: Int,
    internalClientChunkSize: Int,
    externalServerChunkSize: Int,
    internalServerChunkSize: Int) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(address)
    val rawClient = SocketChannel.open(address)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val plainClient = new ChunkingByteChannel(rawClient, chunkSize = externalClientChunkSize)
    val plainServer = new ChunkingByteChannel(rawServer, chunkSize = externalServerChunkSize)
    val clientChannel = new TlsClientSocketChannel(rawClient, createSslEngine(cipher, client = true))
    val serverChannel = new TlsServerSocketChannel(rawServer, n => sslContext, e => e.setEnabledCipherSuites(Array(cipher)))
    val cli = (new ChunkingByteChannel(clientChannel, chunkSize = externalClientChunkSize), clientChannel)
    val ser = (new ChunkingByteChannel(serverChannel, chunkSize = externalClientChunkSize), serverChannel)
    (cli, ser)
  }

  val dataSize = TlsSocketChannelImpl.tlsMaxDataSize * 3

  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  val sslEngine = SSLContext.getDefault.createSSLEngine()

  val ciphers = sslEngine.getSupportedCipherSuites
    // Java 8 disabled SSL through another mechanism, ignore that protocol here, to avoid errors 
    .filter(_.startsWith("TLS_"))
    // not using authentication
    .filter(_.contains("_anon_"))

  def writerLoop(writer: ByteChannel, rawWriter: TlsSocketChannel, renegotiate: Boolean = false): Unit = TestUtil.cannotFail("Error in writer") {
    val renegotiatePeriod = 10000
    logger.debug(s"Starting writer loop, renegotiate:$renegotiate")
    val originData = ByteBuffer.wrap(data)
    var bytesWrittenSinceRenegotiation = 0
    while (originData.hasRemaining) {
      if (renegotiate && bytesWrittenSinceRenegotiation > renegotiatePeriod) {
        rawWriter.renegotiate()
        bytesWrittenSinceRenegotiation = 0
      }
      val c = writer.write(originData)
      bytesWrittenSinceRenegotiation += c
      assert(c > 0)
    }
    logger.debug("Finalizing writer loop")
  }

  def readerLoop(reader: ByteChannel): Unit = TestUtil.cannotFail("Error in reader") {
    logger.debug("Starting reader loop")
    val receivedData = ByteBuffer.allocate(dataSize)
    while (receivedData.hasRemaining) {
      val c = reader.read(receivedData)
      assert(c > 0, "blocking read must return a positive number")
    }
    assert(receivedData.array.deep === data.deep)
    logger.debug("Finalizing reader loop")
  }

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  test("half duplex") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val sizes = Stream.iterate(1)(_ * 3).takeWhileInclusive(_ <= TlsSocketChannelImpl.tlsMaxDataSize)
        for ((size1, size2) <- (sizes zip sizes.reverse)) {
          logger.debug(s"Sizes: size1=$size1,size2=$size2")
          val ((client, clientChannel), (server, serverChannel)) = nioNio(
            cipher,
            internalClientChunkSize = size1,
            externalClientChunkSize = size2,
            internalServerChunkSize = size1,
            externalServerChunkSize = size2)
          val (_, elapsed) = TestUtil.time {
            val clientWriterThread = new Thread(() => writerLoop(client, clientChannel, renegotiate = true), "client-writer")
            val serverWriterThread = new Thread(() => writerLoop(server, serverChannel, renegotiate = true), "server-writer")
            val clientReaderThread = new Thread(() => readerLoop(client), "client-reader")
            val serverReaderThread = new Thread(() => readerLoop(server), "server-reader")
            Seq(serverReaderThread, clientWriterThread).foreach(_.start())
            Seq(serverReaderThread, clientWriterThread).foreach(_.join())
            clientReaderThread.start()
            // renegotiate three times, to test idempotency
            for (_ <- 1 to 3) {
              serverChannel.renegotiate()
            }
            serverWriterThread.start()
            Seq(clientReaderThread, serverWriterThread).foreach(_.join())
            server.close()
            client.close()
          }
          info(f"$cipher%-37s - $size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed / 1000}%5d ms")
        }
      }
    }
  }

  /**
   * Test a full-duplex interaction, without any renegotiation
   */
  test("full duplex") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val sizes = Stream.iterate(1)(_ * 3).takeWhileInclusive(_ <= TlsSocketChannelImpl.tlsMaxDataSize)
        for ((size1, size2) <- (sizes zip sizes.reverse)) {
          val ((client, clientChannel), (server, serverChannel)) = nioNio(cipher,
            internalClientChunkSize = size1,
            externalClientChunkSize = size2,
            internalServerChunkSize = size1,
            externalServerChunkSize = size2)
          val (_, elapsed) = TestUtil.time {
            val clientWriterThread = new Thread(() => writerLoop(client, clientChannel), "client-writer")
            val serverWriterThread = new Thread(() => writerLoop(server, serverChannel), "server-write")
            val clientReaderThread = new Thread(() => readerLoop(client), "client-reader")
            val serverReaderThread = new Thread(() => readerLoop(server), "server-reader")
            Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
            Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
            client.close()
            server.close()
          }
          info(f"$cipher%-37s - $size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed / 1000}%5d ms")
        }
      }
    }
  }

}