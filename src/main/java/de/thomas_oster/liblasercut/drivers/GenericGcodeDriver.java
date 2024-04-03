/*
  This file is part of LibLaserCut.
  Copyright (C) 2011 - 2014 Thomas Oster <mail@thomas-oster.de>

  LibLaserCut is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  LibLaserCut is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
  GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with LibLaserCut. If not, see <http://www.gnu.org/licenses/>.

 */
package de.thomas_oster.liblasercut.drivers;

import de.thomas_oster.liblasercut.FloatPowerSpeedFocusProperty;
import de.thomas_oster.liblasercut.IllegalJobException;
import de.thomas_oster.liblasercut.JobPart;
import de.thomas_oster.liblasercut.LaserCutter;
import de.thomas_oster.liblasercut.LaserJob;
import de.thomas_oster.liblasercut.LaserProperty;
import de.thomas_oster.liblasercut.OptionSelector;
import de.thomas_oster.liblasercut.ProgressListener;
import de.thomas_oster.liblasercut.ProgressListenerDummy;
import de.thomas_oster.liblasercut.RasterizableJobPart;
import de.thomas_oster.liblasercut.utils.LinefeedPrintStream;
import de.thomas_oster.liblasercut.VectorCommand;
import de.thomas_oster.liblasercut.VectorPart;
import de.thomas_oster.liblasercut.platform.Util;
import net.sf.corn.httpclient.HttpClient;
import net.sf.corn.httpclient.HttpResponse;
import purejavacomm.CommPort;
import purejavacomm.CommPortIdentifier;
import purejavacomm.NoSuchPortException;
import purejavacomm.PortInUseException;
import purejavacomm.PureJavaIllegalStateException;
import purejavacomm.SerialPort;
import purejavacomm.UnsupportedCommOperationException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * This class implements a driver for a generic GRBL GCode Lasercutter.
 * It should contain all possible options and is inteded to be the superclass
 * for e.g. the SmoothieBoard and the Lasersaur driver.
 *
 * @author Thomas Oster <thomas.oster@rwth-aachen.de>
 */
public class GenericGcodeDriver extends LaserCutter {

  protected static final String SETTING_HOST = "IP/Hostname";
  protected static final String SETTING_COMPORT = "COM Port";
  protected static final String SETTING_BAUDRATE = "Baud Rate (Serial)";
  protected static final String SETTING_BEDWIDTH = "Laserbed width";
  protected static final String SETTING_BEDHEIGHT = "Laserbed height";
  protected static final String SETTING_FLIP_X = "Flip X Axis";
  protected static final String SETTING_FLIP_Y = "Flip Y Axis";
  protected static final String SETTING_HTTP_UPLOAD_URL = "HTTP Upload URL";
  protected static final String SETTING_AUTOPLAY = "Start Job after HTTP Upload";
  protected static final String SETTING_POST_HTTP_UPLOAD_GCODE = "GCode to send after HTTP Upload";
  protected static final String SETTING_LINEEND = "Lineend (CR,LF,CRLF)";
  protected static final String SETTING_MAX_SPEED = "Max speed (in mm/min)";
  protected static final String SETTING_TRAVEL_SPEED = "Travel (non laser moves) speed (in mm/min)";
  protected static final String SETTING_PRE_JOB_GCODE = "Pre-Job GCode (comma separated)";
  protected static final String SETTING_POST_JOB_GCODE = "Post-Job GCode (comma separated)";
  protected static final String SETTING_RESOLUTIONS = "Supported DPI (comma separated)";
  protected static final String SETTING_IDENTIFICATION_STRING = "Board Identification String (startsWith)";
  protected static final String SETTING_WAIT_FOR_OK = "Wait for OK after each line (interactive mode)";
  protected static final String SETTING_INIT_DELAY = "Seconds to wait for board reset (Serial)";
  protected static final String SETTING_SERIAL_TIMEOUT = "Milliseconds to wait for response";
  protected static final String SETTING_BLANK_LASER_DURING_RAPIDS = "Force laser off during G0 moves";
  protected static final String SETTING_FILE_EXPORT_PATH = "Path to save exported gcode";
  protected static final String SETTING_USE_BIDIRECTIONAL_RASTERING = "Use bidirectional rastering";
  protected static final String SETTING_SPINDLE_MAX = "S value for 100% laser power";
  protected static final String SETTING_UPLOAD_METHOD = "Upload method";
  protected static final String SETTING_RASTER_PADDING = "Extra padding at ends of raster scanlines (mm)";
  protected static final String SETTING_RASTER_PADDING_ALLOW_OUTSIDE_MACHINE_SPACE = "Allow raster padding outside machine limits (negative and positive)";
  protected static final String SETTING_API_KEY = "Api-Key/Password for Octoprint";
  protected static final String SETTING_GCODE_DIGITS = "Decimal places used for XY coordinates";
  protected static final String SETTING_SCODE_DIGITS = "Decimal places used for power (S) value";
  protected static final String SETTING_STATIC_JOBNAME = "Static job (file) name";

  protected static final Locale FORMAT_LOCALE = Locale.US;

  protected static final String UPLOAD_METHOD_FILE = "File";
  protected static final String UPLOAD_METHOD_HTTP = "HTTP";
  protected static final String UPLOAD_METHOD_IP = "IP";
  protected static final String UPLOAD_METHOD_SERIAL = "Serial";
  protected static final String UPLOAD_METHOD_OCTOPRINT = "Octoprint";
  protected static final String UPLOAD_METHOD_GRBLHAL = "grblHAL";

  protected static final String[] uploadMethodList = {UPLOAD_METHOD_FILE, UPLOAD_METHOD_HTTP, UPLOAD_METHOD_IP, UPLOAD_METHOD_SERIAL, UPLOAD_METHOD_OCTOPRINT, UPLOAD_METHOD_GRBLHAL};

  private String lineend = "LF";

  public String getLineend()
  {
    return lineend;
  }

  public void setLineend(String lineend)
  {
    this.lineend = lineend;
  }

  protected String LINEEND()
  {
    return getLineend()
      .replace("LF", "\n")
      .replace("CR", "\r")
      .replace("\\r", "\r")
      .replace("\\n", "\n");
  }

  protected int baudRate = 115200;

  public int getBaudRate()
  {
    return baudRate;
  }

  public void setBaudRate(int baudRate)
  {
    this.baudRate = baudRate;
  }

  protected boolean flipXaxis = false;

  public boolean isFlipXaxis()
  {
    return flipXaxis;
  }

  public void setFlipXaxis(boolean flipXaxis)
  {
    this.flipXaxis = flipXaxis;
  }

  protected boolean flipYaxis = false;

  public boolean isFlipYaxis()
  {
    return flipYaxis;
  }

  public void setFlipYaxis(boolean flipYaxis)
  {
    this.flipYaxis = flipYaxis;
  }

  protected String httpUploadUrl = "http://10.10.10.100/upload";

  public String getHttpUploadUrl()
  {
    return httpUploadUrl;
  }

  public void setHttpUploadUrl(String httpUploadUrl)
  {
    this.httpUploadUrl = httpUploadUrl;
  }

  private boolean autoPlay = true;

  public boolean isAutoPlay()
  {
    return autoPlay;
  }

  public void setAutoPlay(boolean autoPlay)
  {
    this.autoPlay = autoPlay;
  }

  private String postHttpUploadGcode = "";

  public String getPostHttpUploadGcode() { return postHttpUploadGcode; }

  public void setPostHttpUploadGcode(String postHttpUploadGcode) { this.postHttpUploadGcode = postHttpUploadGcode; }

  protected String supportedResolutions = "100,500,1000";

  public String getSupportedResolutions()
  {
    return supportedResolutions;
  }

  public void setSupportedResolutions(String supportedResolutions)
  {
    this.resolutions = null;
    this.supportedResolutions = supportedResolutions;
  }

  protected boolean waitForOKafterEachLine = true;

  public boolean isWaitForOKafterEachLine()
  {
    return waitForOKafterEachLine;
  }

  public void setWaitForOKafterEachLine(boolean waitForOKafterEachLine)
  {
    this.waitForOKafterEachLine = waitForOKafterEachLine;
  }

  public String getIdentificationLine()
  {
    return identificationLine;
  }

  public void setIdentificationLine(String identificationLine)
  {
    this.identificationLine = identificationLine;
  }

  protected String preJobGcode = "G21,G90";

  public String getPreJobGcode()
  {
    return preJobGcode;
  }

  public void setPreJobGcode(String preJobGcode)
  {
    this.preJobGcode = preJobGcode;
  }

  protected String postJobGcode = "G0 X0 Y0";

  public String getPostJobGcode()
  {
    return postJobGcode;
  }

  public void setPostJobGcode(String postJobGcode)
  {
    this.postJobGcode = postJobGcode;
  }

  protected int serialTimeout= 15000;

  public int getSerialTimeout()
  {
    return serialTimeout;
  }

  public void setSerialTimeout(int serialTimeout)
  {
    this.serialTimeout = serialTimeout;
  }

  private String exportPath = "";

  public void setExportPath(String path)
  {
    this.exportPath = path;
  }

  public String getExportPath()
  {
    return exportPath;
  }

  protected String uploadMethod = "";

  public void setUploadMethod(Object method)
  {
    this.uploadMethod = String.valueOf(method);
  }

  public OptionSelector getUploadMethod()
  {
    if (uploadMethod == null || uploadMethod.length() == 0)
    {
      // Determine using original connect() logic
      if (getHost() != null && getHost().length() > 0)
      {
        uploadMethod = UPLOAD_METHOD_IP;
      }
      else if (getComport() != null && !getComport().equals(""))
      {
        uploadMethod = UPLOAD_METHOD_SERIAL;
      }
      else if (getHttpUploadUrl() != null && getHttpUploadUrl().length() > 0)
      {
        uploadMethod = UPLOAD_METHOD_HTTP;
      }
      else if (getExportPath() != null && getExportPath().length() > 0)
      {
        uploadMethod = UPLOAD_METHOD_FILE;
      }
    }

    return new OptionSelector(uploadMethodList, uploadMethod);
  }

  /**
   * What is expected to be received after serial/telnet connection
   * Used e.g. for auto-detecting the serial port.
   */
  protected String identificationLine = "Grbl";

  @Override
  public String getModelName() {
    return "Generic GCode Driver";
  }

  /**
   * Time to wait before firsts reads of serial port.
   * See autoreset feature on arduinos.
   */
  protected int initDelay = 5;

  public int getInitDelay()
  {
    return initDelay;
  }

  public void setInitDelay(int initDelay)
  {
    this.initDelay = initDelay;
  }

  protected String host = "10.10.10.222";

  public String getHost()
  {
    return host;
  }

  public void setHost(String host)
  {
    this.host = host;
  }

  protected String comport = "auto";

  public String getComport()
  {
    return comport;
  }

  public void setComport(String comport)
  {
    this.comport = comport;
  }

  protected double max_speed = 20*60;

  public double getMax_speed()
  {
    return max_speed;
  }

  public void setMax_speed(double max_speed)
  {
    this.max_speed = max_speed;
  }

  protected double travel_speed = 60*60;

  public double getTravel_speed()
  {
    return travel_speed;
  }

  public void setTravel_speed(double travel_speed)
  {
    this.travel_speed = travel_speed;
  }

  protected boolean blankLaserDuringRapids = false;

  public boolean getBlankLaserDuringRapids()
  {
    return blankLaserDuringRapids;
  }

  public void setBlankLaserDuringRapids(boolean blankLaserDuringRapids)
  {
    this.blankLaserDuringRapids = blankLaserDuringRapids;
  }

  /**
   * When rastering, whether to always cut from left to right, or to cut in both
   * directions? (i.e. use the return stroke to raster as well)
   */
  protected boolean useBidirectionalRastering = false;

  public boolean getUseBidirectionalRastering()
  {
    return useBidirectionalRastering;
  }

  public void setUseBidirectionalRastering(boolean useBidirectionalRastering)
  {
    this.useBidirectionalRastering = useBidirectionalRastering;
  }

   /*
   * Value to use for feedrate when laser is 100% on.
   * Varies between firmwares... 1, 100, 255, 10000, etc.
   */
  protected double spindleMax = 1.0;

  public double getSpindleMax()
  {
    return spindleMax;
  }

  public void setSpindleMax(double spindleMax)
  {
    this.spindleMax = spindleMax;
  }

  /**
   * We do not support Frequency atm, so we return power,speed and focus
   */
  @Override
  public LaserProperty getLaserPropertyForVectorPart() {
    return new FloatPowerSpeedFocusProperty();
  }

  @Override
  public LaserProperty getLaserPropertyForRaster3dPart()
  {
    return new FloatPowerSpeedFocusProperty();
  }

  @Override
  public LaserProperty getLaserPropertyForRasterPart()
  {
    return new FloatPowerSpeedFocusProperty();
  }

  protected String formatDouble(double value, int decimalPlaces)
  {
    Locale locale  = new Locale("en", "US");
    DecimalFormat coordinateFormat = (DecimalFormat)NumberFormat.getNumberInstance(locale);
    coordinateFormat.applyPattern("###.##");
    coordinateFormat.setMaximumFractionDigits(decimalPlaces);
    return coordinateFormat.format(value); 
  }

  protected void writeVectorGCode(VectorPart vp, double resolution) throws UnsupportedEncodingException, IOException {
    for (VectorCommand cmd : vp.getCommandList()) {
      switch (cmd.getType()) {
        // TODO: x,y should be changed to double because GCode has infinite vector resolution anyway
        case MOVETO:
          int x = (int) cmd.getX();
          int y = (int) cmd.getY();
          move(out, x, y, resolution);
          break;
        case LINETO:
          x = (int) cmd.getX();
          y = (int) cmd.getY();
          line(out, x, y, resolution);
          break;
        case SETPROPERTY:
          FloatPowerSpeedFocusProperty p = (FloatPowerSpeedFocusProperty) cmd.getProperty();
          setPower(p.getPower());
          setSpeed(p.getSpeed());
          setFocus(out, p.getFocus());
          break;
      }
    }
  }
  protected double currentPower = -1;
  protected double currentSpeed = -1;
  private double nextPower = -1;
  private double nextSpeed = -1;
  private double currentFocus = 0;

  protected void setSpeed(double speedInPercent) {
    nextSpeed = speedInPercent;
  }

  protected void setPower(double powerInPercent) {
    nextPower = powerInPercent/100.0*spindleMax;
  }
  
  protected void setFocus(PrintStream out, double focus) throws IOException {

    if (currentFocus != focus) {
        String append = "";
        if (blankLaserDuringRapids) {
           append = " S0";
           currentPower = -1; // set to invalid value to force new S-value at next G1
        }
        sendLine("G0 Z%s" + append, formatDouble(focus, getGCodeDigits()));
        currentFocus = focus;
    }
  }

  protected void move(PrintStream out, double x, double y, double resolution) throws IOException {
    x = isFlipXaxis() ? getBedWidth() - Util.px2mm(x, resolution) : Util.px2mm(x, resolution);
    y = isFlipYaxis() ? getBedHeight() - Util.px2mm(y, resolution) : Util.px2mm(y, resolution);
    currentSpeed = getTravel_speed();

    if (blankLaserDuringRapids)
    {
      currentPower = -1; // set to invalid value to force new S-value at next G1
      sendLine("G0 X%s Y%s F%d S0", formatDouble(x, getGCodeDigits()), formatDouble(y, getGCodeDigits()), (int) (travel_speed));
    }
    else
    {
      sendLine("G0 X%s Y%s F%d", formatDouble(x, getGCodeDigits()), formatDouble(y, getGCodeDigits()), (int) (travel_speed));
    }
  }

  protected void line(PrintStream out, double x, double y, double resolution) throws IOException {
    x = isFlipXaxis() ? getBedWidth() - Util.px2mm(x, resolution) : Util.px2mm(x, resolution);
    y = isFlipYaxis() ? getBedHeight() - Util.px2mm(y, resolution) : Util.px2mm(y, resolution);
    String append = "";

    if (nextPower != currentPower)
    {
      append += String.format(FORMAT_LOCALE, " S%s", formatDouble(nextPower, getSCodeDigits()));
      currentPower = nextPower;
    }
    if (nextSpeed != currentSpeed)
    {
      append += String.format(FORMAT_LOCALE, " F%d", (int) (max_speed*nextSpeed/100.0));
      currentSpeed = nextSpeed;
    }
    sendLine("G1 X%s Y%s" + append, formatDouble(x, getGCodeDigits()), formatDouble(y, getGCodeDigits()));
  }

  private void writeInitializationCode() throws IOException {
    if (preJobGcode != null)
    {
      for (String line : preJobGcode.split(","))
      {
        sendLine(line);
      }
    }
  }


  private void writeShutdownCode() throws IOException {
    if (postJobGcode != null)
    {
      for (String line : postJobGcode.split(","))
      {
        sendLine(line);
      }
    }
  }

  protected transient BufferedReader in;
  protected transient PrintStream out;
  private transient Socket socket;
  private transient CommPort port;
  private transient CommPortIdentifier portIdentifier;

  protected void sendLine(String text, Object... parameters) throws IOException
  {
    out.format(FORMAT_LOCALE, text+LINEEND(), parameters);
    out.flush();
    if (isWaitForOKafterEachLine())
    {
      String line = waitForLine();
      if (!"ok".equals(line))
      {
        throw new IOException("Lasercutter did not respond 'ok', but '"+line+"'instead.");
      }
    }
  }

  protected void http_upload(URI url, String data, String filename) throws IOException
  {
    HttpClient client = new HttpClient(url);
    client.putAdditionalRequestProperty("X-Filename", filename);
    HttpResponse response = client.sendData(HttpClient.HTTP_METHOD.POST, data);
    if (response == null || response.hasError())
    {
      throw new IOException("Error during POST Request");
    }
  }

  private void http_upload_grblhal(String baseUri, byte[] data, String jobname) throws IOException {
    // Implement https://github.com/grblHAL/Plugin_networking/blob/master/http_upload.c
    // see also https://esp3d.io/esp3d-webui/v3.x/documentation/api/fileupload/index.html

    String filename = jobname;
    CloseableHttpClient httpClient = HttpClients.createDefault();
    HttpPost uploadFile = new HttpPost(baseUri + "?t=" + Long.toString(System.currentTimeMillis()));
    MultipartEntityBuilder builder = MultipartEntityBuilder.create();

    builder.addTextBody("path", "/");
    builder.addTextBody(filename + "S", Integer.toString(data.length));
    builder.addTextBody(filename + "T", DateTimeFormatter.ofPattern("yyyy-MM-hh'T'hh:mm:ss").format(LocalDateTime.now()));

    builder.addBinaryBody(
            "myfiles",
            data,
            ContentType.create("text/x.gcode"),
            filename
    );

    HttpEntity multipart = builder.build();
    uploadFile.setEntity(multipart);
    try (CloseableHttpResponse response = httpClient.execute(uploadFile))
    {
      if (response.getStatusLine().getStatusCode() != 200) {
        throw new IOException("Error: grblHAL returned "+response.getStatusLine().getReasonPhrase());
      }
    }
  }

  protected void http_play(String filename) throws IOException, URISyntaxException
  {
    String command = "play /sd/"+filename;
    http_command(command);
  }

  protected void http_commands(String commands, String filename) throws IOException, URISyntaxException
  {
    for (String command : commands.split(","))
    {
      command = command.replace("$filename", filename);
      http_command(command);
    }
  }

  protected void http_command(String command) throws IOException, URISyntaxException
  {
    command = command + "\n";
    URI url = new URI(getHttpUploadUrl().replace("upload", "command"));
    HttpClient client = new HttpClient(url);
    HttpResponse response = client.sendData(HttpClient.HTTP_METHOD.POST, command);
    if (response == null || response.hasError())
    {
      throw new IOException("Error during POST Request");
    }
  }
  
  protected void octoprint_upload(String host, String apikey, byte[] data, String filename, boolean startPrinting) throws IOException
  {
    //TODO: implement https://docs.octoprint.org/en/master/api/files.html#upload-file-or-create-folder
    CloseableHttpClient httpClient = HttpClients.createDefault();
    HttpPost uploadFile = new HttpPost("http://"+host+"/api/files/local");
    MultipartEntityBuilder builder = MultipartEntityBuilder.create();
    if (startPrinting) {
      builder.addTextBody("print", "true", ContentType.TEXT_PLAIN);
    }
    
    builder.addBinaryBody(
        "file",
        data,
        ContentType.APPLICATION_OCTET_STREAM,
        filename
    );

    HttpEntity multipart = builder.build();
    uploadFile.setEntity(multipart);
    uploadFile.addHeader("X-Api-Key", apikey);
    try (CloseableHttpResponse response = httpClient.execute(uploadFile))
    {
      if (response.getStatusLine().getStatusCode() != 201) {
        throw new IOException("Error: Octoprint returned "+response.getStatusLine().getReasonPhrase());
      }
    }
  }

  protected String waitForLine() throws IOException
  {
    String line = "";
    while ("".equals(line))
    {//skip empty lines
      line = in.readLine();
    }
    return line;
  }

  /**
   * Waits for the Identification line and returns null if it's allright
   * Otherwise it returns the wrong line
   * @param pl Progress listener to update during connect process
   */
  protected String waitForIdentificationLine(ProgressListener pl) throws IOException
  {
    if (getIdentificationLine() != null && getIdentificationLine().length() > 0)
    {
      String line = "";
      for (int trials = 3; trials > 0; trials--)
      {
        line = waitForLine();
        if (line.startsWith(getIdentificationLine()))
        {
          return null;
        }
      }
      return line;
    }
    return null;
  }

  protected String connectSerial(CommPortIdentifier i, ProgressListener pl) throws PortInUseException, IOException, UnsupportedCommOperationException
  {
    pl.taskChanged(this, "opening '"+i.getName()+"'");
    if (i.getPortType() == CommPortIdentifier.PORT_SERIAL)
    {
      try
      {
        port = i.open("VisiCut", 1000);
        try
        {
          port.enableReceiveTimeout(getSerialTimeout());
        }
        catch (UnsupportedCommOperationException e)
        {
          System.err.println("Serial timeout not supported. Driver may hang if device does not respond properly.");
        }
        if (this.getBaudRate() > 0 && port instanceof SerialPort)
        {
          SerialPort sp = (SerialPort) port;
          sp.setSerialPortParams(getBaudRate(), 8, 1, 0);
          sp.setDTR(true);
        }
        out = new PrintStream(port.getOutputStream(), true, StandardCharsets.US_ASCII);
        in = new BufferedReader(new InputStreamReader(port.getInputStream()));
        // Wait 5 seconds since GRBL is long to wake up..
        for (int rest = getInitDelay(); rest > 0; rest--) {
          pl.taskChanged(this, String.format(FORMAT_LOCALE, "Waiting %ds", rest));
          try
          {
            Thread.sleep(1000);
          }
          catch(InterruptedException ex)
          {
            Thread.currentThread().interrupt();
          }
        }
        if (waitForIdentificationLine(pl) != null)
        {
          in.close();
          out.close();
          port.close();
          return "Does not seem to be a "+getModelName()+" on "+i.getName();
        }
        portIdentifier = i;
        pl.taskChanged(this, "Connected");
        return null;
      }
      catch (PortInUseException e)
      {
        try { disconnect(""); } catch (Exception ex) { System.out.println(ex.getMessage()); }
        return "Port in use: "+i.getName();
      }
      catch (IOException e)
      {
        try { disconnect(""); } catch (Exception ex) { System.out.println(ex.getMessage()); }
        return "IO Error from "+i.getName()+": "+e.getMessage();
      }
      catch (PureJavaIllegalStateException e)
      {
        try { disconnect(""); } catch (Exception ex) { System.out.println(ex.getMessage()); }
        return "Could not open "+i.getName()+": "+e.getMessage();
      }
    }
    else
    {
      return "Not a serial Port "+comport;
    }
  }
  /**
   * Used to buffer the file before uploading via http
   */
  private transient ByteArrayOutputStream outputBuffer;
  private transient String jobName;
  protected void connect(ProgressListener pl) throws IOException, PortInUseException, NoSuchPortException, UnsupportedCommOperationException
  {
    outputBuffer = null;
    if (UPLOAD_METHOD_IP.equals(uploadMethod))
    {
      if (getHost() == null || getHost().equals(""))
      {
        throw new IOException("IP/Hostname must be set to upload via IP method");
      }
      socket = new Socket();
      socket.connect(new InetSocketAddress(getHost(), 23), 1000);
      in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      out = new PrintStream(socket.getOutputStream(), true, StandardCharsets.US_ASCII);
      String line = waitForIdentificationLine(pl);
      if (line != null)
      {
        in.close();
        out.close();
        throw new IOException("Wrong identification Line: "+line+"\n instead of "+getIdentificationLine());
      }
    }
    else if (UPLOAD_METHOD_SERIAL.equals(uploadMethod))
    {
      String error = "No serial port found";
      if (portIdentifier == null && !getComport().equals("auto") && !getComport().equals(""))
      {
        try {
          portIdentifier = CommPortIdentifier.getPortIdentifier(getComport());
        }
        catch (NoSuchPortException e) {
          throw new IOException("No such port: "+getComport());
        }
      }

      if (portIdentifier != null)
      {//use port identifier we had last time
        error = connectSerial(portIdentifier, pl);
      }
      else
      {
        Enumeration<CommPortIdentifier> e = CommPortIdentifier.getPortIdentifiers();
        while (e.hasMoreElements())
        {
          CommPortIdentifier i = e.nextElement();
          if (i.getPortType() == CommPortIdentifier.PORT_SERIAL)
          {
            error = connectSerial(i, pl);
            if (error == null)
            {
              break;
            }
          }
        }
      }
      if (error != null)
      {
        throw new IOException(error);
      }
    }
    else if (UPLOAD_METHOD_HTTP.equals(uploadMethod) || UPLOAD_METHOD_GRBLHAL.equals(uploadMethod))
    {
      if (getHttpUploadUrl() == null || getHttpUploadUrl().equals(""))
      {
        throw new IOException("HTTP Upload URL must be set to upload via HTTP method");
      }
      outputBuffer = new ByteArrayOutputStream();
      out = new PrintStream(outputBuffer);
      setWaitForOKafterEachLine(false);
      in = null;
    }
    else if (UPLOAD_METHOD_FILE.equals(uploadMethod))
    {
      if (getExportPath() == null || getExportPath().equals(""))
      {
        throw new IOException("Export Path must be set to upload via File method.");
      }
      File file = new File(getExportPath(), this.jobName);
      out = new PrintStream(new FileOutputStream(file));
      setWaitForOKafterEachLine(false);
      in = null;
    }
    else if (UPLOAD_METHOD_OCTOPRINT.equals(uploadMethod))
    {
      if (StringUtils.isAllBlank(getApiKey())) {
        throw new IOException("API-Key must be set to upload via Octoprint method.");
      }
      if (StringUtils.isAllBlank(getHost())) {
        throw new IOException("HOST/IP must be set to upload via Octoprint method.");
      }
      outputBuffer = new ByteArrayOutputStream();
      out = new PrintStream(outputBuffer);
      setWaitForOKafterEachLine(false);
      in = null;
    }
    else
    {
      throw new IOException("Upload Method must be set");
    }
  }

  protected void disconnect(String jobname) throws IOException, URISyntaxException
  {
    if (UPLOAD_METHOD_HTTP.equals(uploadMethod))
    {
      out.close();
      http_upload(new URI(getHttpUploadUrl()), outputBuffer.toString(StandardCharsets.UTF_8), jobname);
      if (this.getPostHttpUploadGcode() != null && !this.getPostHttpUploadGcode().equals(""))
      {
        http_commands(this.getPostHttpUploadGcode(), jobname);
      }
      if (this.isAutoPlay())
      {
        http_play(jobname);
      }
    }
    else if (UPLOAD_METHOD_OCTOPRINT.equals(uploadMethod)) {
      out.close();
      octoprint_upload(getHost(), getApiKey(), outputBuffer.toByteArray(), jobname, this.isAutoPlay());
    }
    else if (UPLOAD_METHOD_GRBLHAL.equals(uploadMethod))
    {
      out.close();
      http_upload_grblhal(getHttpUploadUrl(), outputBuffer.toByteArray(), jobname);
    }
    else
    {
      if (in != null)
      {
        in.close();
      }
      out.close();
      if (this.socket != null)
      {
        socket.close();
        socket = null;
      }
      else if (this.port != null)
      {
        this.port.close();
        this.port = null;
      }
    }

  }

  @Override
  public void sendJob(LaserJob job, ProgressListener pl, List<String> warnings) throws IllegalJobException, Exception {
    pl.progressChanged(this, 0);
    this.currentPower = -1;
    this.currentSpeed = -1;

    pl.taskChanged(this, "checking job");
    checkJob(job);
    if (getStaticJobName() != null && !getStaticJobName().isEmpty()) {
      this.jobName = getStaticJobName();
    } else {
      this.jobName = job.getName() + ".gcode";
    }
    job.applyStartPoint();
    pl.taskChanged(this, "connecting...");
    connect(pl);
    pl.taskChanged(this, "sending");
    try {
      writeJobCode(job, pl);
      disconnect(this.jobName);
    }
    catch (IOException e) {
      pl.taskChanged(this, "disconnecting");
      disconnect(this.jobName);
      throw e;
    }
    pl.taskChanged(this, "sent.");
    pl.progressChanged(this, 100);
  }
  
  public void writeJobCode(LaserJob job, ProgressListener pl) throws IOException {
    writeInitializationCode();
    pl.progressChanged(this, 20);
    int i = 0;
    int max = job.getParts().size();
    for (JobPart p : job.getParts())
    {
      if (p instanceof RasterizableJobPart)
      {
        // Note: It's difficult to choose "the right" setting for whether to use moveto() or lineto() for white engrave pixels.
        // For smooth engraving and compatibility with previous LibLaserCut versions, we use lineto().
        // This won't work with boards that ignore the laser power setting (S0 ... S1) and only consider G0/G1 (move/line).
        // Therefore it should be made configurable.
        p = convertRasterizableToVectorPart((RasterizableJobPart) p, job, getUseBidirectionalRastering(), false, false);
      }
      if (p instanceof VectorPart)
      {
        //TODO: in direct mode use progress listener to indicate progress
        //of individual job
        writeVectorGCode((VectorPart) p, p.getDPI());
      }
      i++;
      pl.progressChanged(this, 20 + (int) (i*(double) 60/max));
    }
    writeShutdownCode();
  }

@Override
public void saveJob(OutputStream fileOutputStream, LaserJob job) throws IllegalJobException, Exception {
  this.currentPower = -1;
  this.currentSpeed = -1;

  checkJob(job);
  boolean wasSetWaitingForOk = isWaitForOKafterEachLine();
  try (PrintStream ps = new LinefeedPrintStream(fileOutputStream))
  {
    this.out = ps;
    setWaitForOKafterEachLine( false );
    writeJobCode(job, new ProgressListenerDummy());
  } finally {
    setWaitForOKafterEachLine(wasSetWaitingForOk);
  }
}

  @Override
  public boolean canEstimateJobDuration() {
    return true;
  }
  
  @Override
  public int estimateJobDuration(LaserJob job) {
    // getTravel_speed() and getMax_speed() are in mm/min, estimateJobDuration(...) uses mm/s.
    return estimateJobDuration(job, getTravel_speed() / 60, getTravel_speed() / 60, getMax_speed() / 60, 0, getMax_speed() / 60, 0, getMax_speed() / 60);
  }
    

  private transient List<Double> resolutions;

  @Override
  public List<Double> getResolutions() {
    if (resolutions == null) {
      resolutions = new LinkedList<>();
      for (String s : getSupportedResolutions().split(","))
      {
        resolutions.add(Double.parseDouble(s));
      }
    }
    return resolutions;
  }
  protected double bedWidth = 250;

  /**
   * Get the value of bedWidth
   *
   * @return the value of bedWidth
   */
  @Override
  public double getBedWidth() {
    return bedWidth;
  }

  /**
   * Set the value of bedWidth
   *
   * @param bedWidth new value of bedWidth
   */
  public void setBedWidth(double bedWidth) {
    this.bedWidth = bedWidth;
  }
  protected double bedHeight = 280;

  /**
   * Get the value of bedHeight
   *
   * @return the value of bedHeight
   */
  @Override
  public double getBedHeight() {
    return bedHeight;
  }

  /**
   * Set the value of bedHeight
   *
   * @param bedHeight new value of bedHeight
   */
  public void setBedHeight(double bedHeight) {
    this.bedHeight = bedHeight;
  }

  protected double rasterPadding = 5;

  /**
   * Get the amount of padding at each end of a raster scanline
   *
   * @return the value of rasterPadding
   */
  @Override
  public double getRasterPadding() {
    return rasterPadding;
  }

  /**
   * Set the amount of padding at each end of a raster scanline
   *
   * @param rasterPadding new value of rasterPadding
   */
  public void setRasterPadding(double rasterPadding) {
    this.rasterPadding = rasterPadding;
  }

  protected boolean rasterPaddingAllowOutsideMachineSpace = false;

  /**
   * Whether padding is allowed to go into negative space or not
   *
   * @return the value of rasterPaddingAllowNegativeSpace
   */
  @Override
  public boolean getRasterPaddingAllowOutsideMachineSpace() {
    return rasterPaddingAllowOutsideMachineSpace;
  }

  public void setRasterPaddingAllowOutsideMachineSpace(boolean allowOutsideMachineSpace) {
    this.rasterPaddingAllowOutsideMachineSpace = allowOutsideMachineSpace;
  }
  
  private String apiKey;

  public String getApiKey()
  {
    return apiKey;
  }

  public void setApiKey(String apiKey)
  {
    this.apiKey = apiKey;
  }
  
  private Integer gCodeDigits = 6;

  public Integer getGCodeDigits()
  {
    if (gCodeDigits == null) gCodeDigits = 6;
    return gCodeDigits;
  }
  public void setGCodeDigits(Integer gCodeDigits)
  {
    this.gCodeDigits = gCodeDigits;
  }
  
  private Integer sCodeDigits = 3;

  public Integer getSCodeDigits()
  {
    if (sCodeDigits == null) sCodeDigits = 3;
    return sCodeDigits;
  }
  public void setSCodeDigits(Integer sCodeDigits)
  {
    this.sCodeDigits = sCodeDigits;
  }

  private String staticJobName;

  public String getStaticJobName()
  {
    return staticJobName;
  }

  public void setStaticJobName(String staticJobName)
  {
    this.staticJobName = staticJobName;
  }
  private static final String[] SETTINGS_LIST = new String[]{
    SETTING_UPLOAD_METHOD,
    SETTING_BAUDRATE,
    SETTING_BEDWIDTH,
    SETTING_BEDHEIGHT,
    SETTING_COMPORT,
    SETTING_FLIP_X,
    SETTING_FLIP_Y,
    SETTING_HOST,
    SETTING_HTTP_UPLOAD_URL,
    SETTING_AUTOPLAY,
    SETTING_POST_HTTP_UPLOAD_GCODE,
    SETTING_IDENTIFICATION_STRING,
    SETTING_INIT_DELAY,
    SETTING_LINEEND,
    SETTING_MAX_SPEED,
    SETTING_TRAVEL_SPEED,
    SETTING_SPINDLE_MAX,
    SETTING_BLANK_LASER_DURING_RAPIDS,
    SETTING_PRE_JOB_GCODE,
    SETTING_POST_JOB_GCODE,
    SETTING_RESOLUTIONS,
    SETTING_WAIT_FOR_OK,
    SETTING_SERIAL_TIMEOUT,
    SETTING_FILE_EXPORT_PATH,
    SETTING_USE_BIDIRECTIONAL_RASTERING,
    SETTING_RASTER_PADDING,
    SETTING_RASTER_PADDING_ALLOW_OUTSIDE_MACHINE_SPACE,
    SETTING_API_KEY,
    SETTING_GCODE_DIGITS,
    SETTING_SCODE_DIGITS,
    SETTING_STATIC_JOBNAME
  };

  @Override
  public String[] getPropertyKeys() {
    return SETTINGS_LIST;
  }

  @Override
  public Object getProperty(String attribute) {
    if (SETTING_HOST.equals(attribute)) {
      return this.getHost();
    } else if (SETTING_BAUDRATE.equals(attribute)) {
      return this.getBaudRate();
    } else if (SETTING_BEDWIDTH.equals(attribute)) {
      return this.getBedWidth();
    } else if (SETTING_BEDHEIGHT.equals(attribute)) {
      return this.getBedHeight();
    } else if (SETTING_COMPORT.equals(attribute)) {
      return this.getComport();
    } else if (SETTING_FLIP_X.equals(attribute)) {
      return this.isFlipXaxis();
    } else if (SETTING_FLIP_Y.equals(attribute)) {
      return this.isFlipYaxis();
    } else if (SETTING_HTTP_UPLOAD_URL.equals(attribute)) {
      return this.getHttpUploadUrl();
    } else if (SETTING_AUTOPLAY.equals(attribute)) {
      return this.isAutoPlay();
    } else if (SETTING_POST_HTTP_UPLOAD_GCODE.equals(attribute)) {
      return this.getPostHttpUploadGcode();
    } else if (SETTING_IDENTIFICATION_STRING.equals(attribute)) {
      return this.getIdentificationLine();
    } else if (SETTING_INIT_DELAY.equals(attribute)) {
      return this.getInitDelay();
    } else if (SETTING_LINEEND.equals(attribute)) {
      return this.getLineend();
    } else if (SETTING_MAX_SPEED.equals(attribute)) {
      return this.getMax_speed();
    } else if (SETTING_TRAVEL_SPEED.equals(attribute)) {
      return this.getTravel_speed();
    } else if (SETTING_PRE_JOB_GCODE.equals(attribute)) {
      return this.getPreJobGcode();
    } else if (SETTING_POST_JOB_GCODE.equals(attribute)) {
      return this.getPostJobGcode();
    } else if (SETTING_RESOLUTIONS.equals(attribute)) {
      return this.getSupportedResolutions();
    } else if (SETTING_WAIT_FOR_OK.equals(attribute)) {
      return this.isWaitForOKafterEachLine();
    } else if (SETTING_SERIAL_TIMEOUT.equals(attribute)) {
      return this.getSerialTimeout();
    } else if (SETTING_BLANK_LASER_DURING_RAPIDS.equals(attribute)) {
      return this.getBlankLaserDuringRapids();
    } else if (SETTING_FILE_EXPORT_PATH.equals(attribute)) {
      return this.getExportPath();
    } else if (SETTING_USE_BIDIRECTIONAL_RASTERING.equals(attribute)) {
      return this.getUseBidirectionalRastering();
    } else if (SETTING_SPINDLE_MAX.equals(attribute)) {
      return this.getSpindleMax();
    } else if (SETTING_UPLOAD_METHOD.equals(attribute)) {
      return this.getUploadMethod();
    } else if (SETTING_RASTER_PADDING.equals(attribute)) {
      return this.getRasterPadding();
    } else if (SETTING_RASTER_PADDING_ALLOW_OUTSIDE_MACHINE_SPACE.equals(attribute)) {
      return this.getRasterPaddingAllowOutsideMachineSpace();
    } else if (SETTING_API_KEY.equals(attribute)) {
      return this.getApiKey();
    } else if (SETTING_GCODE_DIGITS.equals(attribute)) {
      return this.getGCodeDigits();
    } else if (SETTING_SCODE_DIGITS.equals(attribute)) {
      return this.getSCodeDigits();
    } else if (SETTING_STATIC_JOBNAME.equals(attribute)) {
      return this.getStaticJobName();
    }

    return null;
  }

  @Override
  public void setProperty(String attribute, Object value) {
    if (SETTING_HOST.equals(attribute)) {
      this.setHost((String) value);
    } else if (SETTING_BAUDRATE.equals(attribute)) {
      this.setBaudRate((Integer) value);
    } else if (SETTING_BEDWIDTH.equals(attribute)) {
      this.setBedWidth((Double) value);
    } else if (SETTING_BEDHEIGHT.equals(attribute)) {
      this.setBedHeight((Double) value);
    } else if (SETTING_COMPORT.equals(attribute)) {
      this.setComport((String) value);
    } else if (SETTING_FLIP_X.equals(attribute)) {
      this.setFlipXaxis((Boolean) value);
    } else if (SETTING_FLIP_Y.equals(attribute)) {
      this.setFlipYaxis((Boolean) value);
    } else if (SETTING_HTTP_UPLOAD_URL.equals(attribute)) {
      this.setHttpUploadUrl((String) value);
    } else if (SETTING_AUTOPLAY.equals(attribute)) {
      this.setAutoPlay((Boolean) value);
    } else if (SETTING_POST_HTTP_UPLOAD_GCODE.equals(attribute)) {
      this.setPostHttpUploadGcode((String) value);
    } else if (SETTING_IDENTIFICATION_STRING.equals(attribute)) {
      this.setIdentificationLine((String) value);
    } else if (SETTING_INIT_DELAY.equals(attribute)) {
      this.setInitDelay((Integer) value);
    } else if (SETTING_LINEEND.equals(attribute)) {
      this.setLineend((String) value);
    } else if (SETTING_MAX_SPEED.equals(attribute)) {
      this.setMax_speed((Double) value);
    } else if (SETTING_TRAVEL_SPEED.equals(attribute)) {
      this.setTravel_speed((Double) value);
    } else if (SETTING_PRE_JOB_GCODE.equals(attribute)) {
      this.setPreJobGcode((String) value);
    } else if (SETTING_POST_JOB_GCODE.equals(attribute)) {
      this.setPostJobGcode((String) value);
    } else if (SETTING_RESOLUTIONS.equals(attribute)) {
      this.setSupportedResolutions((String) value);
    } else if (SETTING_WAIT_FOR_OK.equals(attribute)) {
      this.setWaitForOKafterEachLine((Boolean) value);
    } else if (SETTING_SERIAL_TIMEOUT.equals(attribute)) {
      this.setSerialTimeout((Integer) value);
    } else if (SETTING_BLANK_LASER_DURING_RAPIDS.equals(attribute)) {
      this.setBlankLaserDuringRapids((Boolean) value);
    } else if (SETTING_FILE_EXPORT_PATH.equals(attribute)) {
      this.setExportPath((String) value);
    } else if (SETTING_USE_BIDIRECTIONAL_RASTERING.equals(attribute)) {
      this.setUseBidirectionalRastering((Boolean) value);
    } else if (SETTING_SPINDLE_MAX.equals(attribute)) {
      this.setSpindleMax((Double) value);
    } else if (SETTING_UPLOAD_METHOD.equals(attribute)) {
      this.setUploadMethod(value);
    } else if (SETTING_RASTER_PADDING.equals(attribute)) {
      this.setRasterPadding(Math.abs((Double)value));
    } else if (SETTING_RASTER_PADDING_ALLOW_OUTSIDE_MACHINE_SPACE.equals(attribute)) {
      this.setRasterPaddingAllowOutsideMachineSpace((Boolean) value);
    } else if (SETTING_API_KEY.equals(attribute)) {
      this.setApiKey((String) value);
    } else if (SETTING_GCODE_DIGITS.equals(attribute)) {
      this.setGCodeDigits((Integer) value);
    } else if (SETTING_SCODE_DIGITS.equals(attribute)) {
      this.setSCodeDigits((Integer) value);
    } else if (SETTING_STATIC_JOBNAME.equals(attribute)) {
      this.setStaticJobName((String) value);
    }
  }

  /**
   * Adjust defaults after deserializing driver from an old version of XML file
   */
  @Override
  protected void setKeysMissingFromDeserialization()
  {
    // added field spindleMax, needs to be set to 1.0 by default
    // but xstream initializes it to 0.0 when it is missing from XML
    if (this.spindleMax <= 0.0) this.spindleMax = 1.0;
  }

  @Override
  public GenericGcodeDriver clone() {
    GenericGcodeDriver clone = new GenericGcodeDriver();
    clone.copyProperties(this);
    return clone;
  }

}
